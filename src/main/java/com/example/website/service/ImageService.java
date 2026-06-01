package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.config.OkHttpConfig;
import com.example.website.dto.ImageEditRequest;
import com.example.website.dto.ImageGenerateRequest;
import com.example.website.dto.ImageGenerationsResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

    public static final String CFG_BASE_URL    = "image.api.baseUrl";
    public static final String CFG_API_KEY     = "image.api.key";
    public static final String CFG_MODEL       = "image.api.model";
    public static final String CFG_EDIT_BASE_URL = "image.edit.api.baseUrl";
    public static final String CFG_UPLOAD_DIR  = "image.upload.dir";
    public static final String CFG_GOFILE_URL  = "image.gofile.url";
    public static final String CFG_GOFILE_TOKEN = "image.gofile.token";
    public static final String CFG_REMOTE_URL_MODE = "image.persist.remote-url-mode";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Pattern MARKDOWN_IMAGE_PATTERN =
            Pattern.compile("!\\[[^\\]]*]\\(((?:https?://|data:image/)[^)\\s]+)\\)");
    private static final Pattern DATA_URL_PATTERN =
            Pattern.compile("data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s)\"']+");
    private static final Pattern GPT_IMAGE_MODEL_PATTERN =
            Pattern.compile("^gpt-image(?:[-_].+)?$", Pattern.CASE_INSENSITIVE);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    private final SysConfigService configService;
    @Qualifier(OkHttpConfig.CLIENT_IMAGE_UPSTREAM)
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final Executor imageGenSubExecutor;

    public ImageGenerationsResponse generate(ImageGenerateRequest req) {
        String baseUrl = configService.getValueOrThrow(CFG_BASE_URL);
        String apiKey  = configService.getValueOrThrow(CFG_API_KEY);
        String model   = configService.getValueOrThrow(CFG_MODEL);
        int n = req.getN() != null ? req.getN() : 1;

        boolean imagesEndpoint = isImagesEndpoint(baseUrl);
        String requestUrl = baseUrl;
        if (!imagesEndpoint && isChatCompletionsEndpoint(baseUrl) && isGptImageModel(model)) {
            requestUrl = deriveImagesEndpoint(baseUrl);
            imagesEndpoint = true;
            log.info("Auto-routing image model {} from {} to {}", model, baseUrl, requestUrl);
        }

        if (imagesEndpoint) {
            return generateImagesEndpoint(requestUrl, apiKey, model, req.getPrompt(), n, req.getSize());
        }
        return generateChatEndpoint(requestUrl, apiKey, model, req.getPrompt(), n, req.getSize());
    }

    public ImageGenerationsResponse edit(ImageEditRequest req) {
        String baseUrl = configService.getValue(CFG_EDIT_BASE_URL)
                .filter(v -> v != null && !v.trim().isEmpty())
                .orElseGet(() -> deriveEditsEndpoint(configService.getValueOrThrow(CFG_BASE_URL)));
        String apiKey = configService.getValueOrThrow(CFG_API_KEY);
        String model = configService.getValueOrThrow(CFG_MODEL);
        int n = req.getN() != null ? req.getN() : 1;

        UpstreamCallResult upstream = callUpstreamMultipart(baseUrl, apiKey, buildEditMultipartBody(model, req, n));
        ImageGenerationsResponse parsed = parseImagesResponse(model, upstream.rawBody, upstream.status, n);
        if (parsed != null) {
            return parsed;
        }
        throw new BusinessException(502, "Upstream returned no edited image URL");
    }

    private ImageGenerationsResponse generateImagesEndpoint(String url, String apiKey, String model,
                                                            String prompt, int n, String size) {
        String bodyJson = buildImagesBody(model, prompt, n, size);
        String fallbackBodyJson = buildMinimalImagesBody(model, prompt, n);
        boolean hasSize = size != null && !size.isEmpty();

        try {
            UpstreamCallResult upstream = callUpstream(url, apiKey, true, bodyJson);
            ImageGenerationsResponse parsed = parseImagesResponse(model, upstream.rawBody, upstream.status, n);
            if (parsed != null) return parsed;
        } catch (BusinessException e) {
            if (!hasSize) {
                throw e;
            }
            log.warn("Primary images request with size failed. Retrying with minimal body: {}", e.getMessage());
            UpstreamCallResult fallback = callUpstream(url, apiKey, true, fallbackBodyJson);
            ImageGenerationsResponse parsed = parseImagesResponse(model, fallback.rawBody, fallback.status, n);
            if (parsed != null) return parsed;
            throw e;
        }

        log.warn("Primary images request returned no image. Retrying with minimal body.");
        UpstreamCallResult fallback = callUpstream(url, apiKey, true, fallbackBodyJson);
        ImageGenerationsResponse parsed = parseImagesResponse(model, fallback.rawBody, fallback.status, n);
        if (parsed != null) return parsed;

        throw new BusinessException(502, "Upstream returned no image URL");
    }

    private ImageGenerationsResponse generateChatEndpoint(String url, String apiKey, String model,
                                                          String prompt, int n, String size) {
        String effectivePrompt = buildPromptWithSize(prompt, size);
        List<CompletableFuture<ImageGenerationsResponse.ImageDataItem>> futures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                String bodyJson = buildChatBody(model, effectivePrompt);
                UpstreamCallResult upstream = callUpstream(url, apiKey, false, bodyJson);
                return parseChatResponse(prompt, model, upstream.rawBody, upstream.status);
            }, imageGenSubExecutor));
        }

        List<ImageGenerationsResponse.ImageDataItem> data;
        try {
            data = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream()
                            .map(CompletableFuture::join)
                            .collect(Collectors.toList()))
                    .get();
        } catch (Exception e) {
            log.error("Concurrent chat image generation failed", e);
            throw new BusinessException(502, "Concurrent image generation failed: " + e.getMessage());
        }

        long failed = data.stream().filter(d -> d.getUrl() == null).count();
        if (failed == data.size()) {
            throw new BusinessException(502, "All concurrent image requests returned no image URL");
        }
        if (failed > 0) {
            log.warn("{}/{} concurrent image requests returned no URL", failed, data.size());
        }

        return new ImageGenerationsResponse(
                Instant.now().getEpochSecond(), model, data, null);
    }

    private ImageGenerationsResponse.ImageDataItem parseChatResponse(String prompt, String model,
                                                                     String rawBody, int status) {
        String trimmed = rawBody.trim();
        if (trimmed.startsWith("data:")) {
            Map<String, Object> parsed = parseChatSse(model, trimmed);
            return toImageDataItem(parsed);
        }
        Map<String, Object> json = readJsonOrThrow(rawBody, status);
        throwIfUpstreamError(json);
        if (status >= 400) {
            log.warn("Chat image upstream returned status {}: {}", status, truncate(rawBody));
            return new ImageGenerationsResponse.ImageDataItem();
        }
        String content = extractChatContent(json);
        if (content == null || content.isEmpty()) {
            log.warn("Chat image upstream returned no content: {}", truncate(rawBody));
            return new ImageGenerationsResponse.ImageDataItem();
        }
        String url = extractUrlFromText(content);
        return new ImageGenerationsResponse.ImageDataItem(url, null, null);
    }

    private ImageGenerationsResponse.ImageDataItem toImageDataItem(Map<String, Object> parsed) {
        if (parsed == null) return new ImageGenerationsResponse.ImageDataItem();
        String content = parsed.getOrDefault("content", "").toString();
        String url = extractUrlFromText(content);
        return new ImageGenerationsResponse.ImageDataItem(url, null, null);
    }

    private String buildChatBody(String model, String prompt) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("stream", false);
        return writeJson(payload);
    }

    private String buildPromptWithSize(String prompt, String size) {
        if (size == null || size.isEmpty()) {
            return prompt;
        }
        String[] parts = size.split("x");
        if (parts.length == 2) {
            return String.format("%s×%s %s", parts[0], parts[1], prompt);
        }
        return prompt + " " + size;
    }

    private String buildImagesBody(String model, String prompt, int n, String size) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("prompt", prompt);
        payload.put("n", n);
        if (size != null && !size.isEmpty()) {
            payload.put("size", size);
        }
        return writeJson(payload);
    }

    private String buildMinimalImagesBody(String model, String prompt, int n) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("prompt", prompt);
        payload.put("n", n);
        return writeJson(payload);
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "Failed to serialize request: " + e.getOriginalMessage());
        }
    }

    private boolean isImagesEndpoint(String baseUrl) {
        return baseUrl != null && baseUrl.contains("/images/");
    }

    private boolean isChatCompletionsEndpoint(String baseUrl) {
        return baseUrl != null && baseUrl.contains("/chat/completions");
    }

    private boolean isGptImageModel(String model) {
        return model != null && GPT_IMAGE_MODEL_PATTERN.matcher(model).matches();
    }

    private String deriveImagesEndpoint(String baseUrl) {
        return baseUrl.replace("/chat/completions", "/images/generations");
    }

    private String deriveEditsEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new BusinessException(500, "image.api.baseUrl is not configured");
        }
        String trimmed = baseUrl.trim();
        if (trimmed.contains("/images/edits")) {
            return trimmed;
        }
        if (trimmed.contains("/images/generations")) {
            return trimmed.replace("/images/generations", "/images/edits");
        }
        if (trimmed.contains("/chat/completions")) {
            return trimmed.replace("/chat/completions", "/images/edits");
        }
        if (trimmed.endsWith("/")) {
            return trimmed + "images/edits";
        }
        return trimmed + "/images/edits";
    }

    private RequestBody buildEditMultipartBody(String model, ImageEditRequest req, int n) {
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("prompt", req.getPrompt())
                .addFormDataPart("n", String.valueOf(n));
        if (req.getSize() != null && !req.getSize().isEmpty()) {
            builder.addFormDataPart("size", req.getSize());
        }
        builder.addFormDataPart(
                "image",
                safeFilename(req.getImageFilename(), "image.png"),
                RequestBody.create(req.getImageBytes(), mediaTypeOrDefault(req.getImageContentType()))
        );
        if (req.getMaskBytes() != null && req.getMaskBytes().length > 0) {
            builder.addFormDataPart(
                    "mask",
                    safeFilename(req.getMaskFilename(), "mask.png"),
                    RequestBody.create(req.getMaskBytes(), mediaTypeOrDefault(req.getMaskContentType()))
            );
        }
        return builder.build();
    }

    private UpstreamCallResult callUpstream(String requestUrl, String apiKey, boolean imagesEndpoint, String bodyJson) {
        Request request = new Request.Builder()
                .url(requestUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", imagesEndpoint ? "application/json" : "*/*")
                .post(RequestBody.create(bodyJson, JSON))
                .build();

        String rawBody;
        int status;
        try (Response response = okHttpClient.newCall(request).execute()) {
            status = response.code();
            ResponseBody rb = response.body();
            rawBody = rb == null ? "" : rb.string();
        } catch (IOException e) {
            log.error("Image generation upstream I/O failed", e);
            throw new BusinessException(502, "Upstream image API I/O failed: " + e.getMessage());
        }

        if (rawBody == null || rawBody.isEmpty()) {
            throw new BusinessException(502, "Empty response from upstream (status=" + status + ")");
        }
        return new UpstreamCallResult(status, rawBody);
    }

    private UpstreamCallResult callUpstreamMultipart(String requestUrl, String apiKey, RequestBody body) {
        Request request = new Request.Builder()
                .url(requestUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .post(body)
                .build();

        String rawBody;
        int status;
        try (Response response = okHttpClient.newCall(request).execute()) {
            status = response.code();
            ResponseBody rb = response.body();
            rawBody = rb == null ? "" : rb.string();
        } catch (IOException e) {
            log.error("Image edit upstream I/O failed", e);
            throw new BusinessException(502, "Upstream image edit API I/O failed: " + e.getMessage());
        }

        if (rawBody == null || rawBody.isEmpty()) {
            throw new BusinessException(502, "Empty response from upstream (status=" + status + ")");
        }
        return new UpstreamCallResult(status, rawBody);
    }

    @SuppressWarnings("unchecked")
    private ImageGenerationsResponse parseImagesResponse(String model, String rawBody, int status, int requestedN) {
        Map<String, Object> raw = readJsonOrThrow(rawBody, status);
        throwIfUpstreamError(raw);
        if (status >= 400) {
            throw new BusinessException(502, "Upstream image API returned " + status);
        }

        Object dataObj = raw.get("data");
        if (!(dataObj instanceof List)) {
            log.warn("Images endpoint returned no data array: {}", truncate(rawBody));
            return null;
        }
        List<Object> dataList = (List<Object>) dataObj;
        if (dataList.isEmpty()) {
            log.warn("Images endpoint returned empty data array: {}", truncate(rawBody));
            return null;
        }

        List<ImageGenerationsResponse.ImageDataItem> items = new ArrayList<>();
        for (Object item : dataList) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> map = (Map<String, Object>) item;
            String url = null;
            Object urlVal = map.get("url");
            if (urlVal instanceof String && !((String) urlVal).isEmpty()) {
                url = (String) urlVal;
            }
            String b64 = null;
            Object b64Val = map.get("b64_json");
            if (b64Val instanceof String && !((String) b64Val).isEmpty()) {
                b64 = (String) b64Val;
            }
            if (url == null && b64 == null) continue;
            if (url == null && b64 != null) {
                url = "data:image/png;base64," + b64;
            }
            String revised = map.get("revised_prompt") instanceof String
                    ? (String) map.get("revised_prompt") : null;
            items.add(new ImageGenerationsResponse.ImageDataItem(url, b64, revised));
        }

        if (items.isEmpty()) {
            log.warn("Images endpoint data array had no recognizable image items: {}", truncate(rawBody));
            return null;
        }

        Object createdObj = raw.get("created");
        Long created = createdObj instanceof Number ? ((Number) createdObj).longValue() : Instant.now().getEpochSecond();

        Object usageObj = raw.get("usage");
        Map<String, Object> usage = usageObj instanceof Map ? (Map<String, Object>) usageObj : null;

        return new ImageGenerationsResponse(created, model, items, usage);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseChatSse(String model, String rawBody) {
        StringBuilder content = new StringBuilder();
        Map<String, Object> lastMeta = null;
        String embeddedError = null;

        for (String line : rawBody.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (!trimmed.startsWith("data:")) {
                if (trimmed.startsWith("{") && trimmed.contains("\"error\"")) {
                    embeddedError = extractErrorMessage(trimmed);
                }
                continue;
            }
            String payload = trimmed.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) continue;

            Map<String, Object> chunk;
            try {
                chunk = objectMapper.readValue(payload, MAP_TYPE);
            } catch (JsonProcessingException e) {
                log.warn("Skipping unparseable SSE chunk: {}", payload);
                continue;
            }
            lastMeta = chunk;
            Object choicesObj = chunk.get("choices");
            if (!(choicesObj instanceof List)) continue;
            List<?> choices = (List<?>) choicesObj;
            if (choices.isEmpty() || !(choices.get(0) instanceof Map)) continue;
            Object deltaObj = ((Map<?, ?>) choices.get(0)).get("delta");
            if (!(deltaObj instanceof Map)) continue;
            Object text = ((Map<?, ?>) deltaObj).get("content");
            if (text != null) content.append(text);
        }

        String full = content.toString();
        String imageUrl = extractUrlFromText(full);
        if (embeddedError != null && imageUrl == null && full.isEmpty()) {
            throw new BusinessException(502, "Upstream error: " + embeddedError);
        }
        if (full.isEmpty() && imageUrl == null) {
            throw new BusinessException(502, "Upstream returned no content (unstable). Retry.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imageUrl", imageUrl);
        result.put("content", full);
        return result;
    }

    private Map<String, Object> readJsonOrThrow(String rawBody, int status) {
        try {
            return objectMapper.readValue(rawBody, MAP_TYPE);
        } catch (JsonProcessingException e) {
            log.error("Non-JSON upstream response (status={}): {}", status, truncate(rawBody));
            throw new BusinessException(502, "Invalid JSON from upstream (status=" + status + ")");
        }
    }

    private void throwIfUpstreamError(Map<String, Object> raw) {
        Object errorObj = raw.get("error");
        if (errorObj instanceof Map) {
            Object msg = ((Map<?, ?>) errorObj).get("message");
            throw new BusinessException(502, "Upstream error: " + (msg != null ? msg : errorObj));
        }
    }

    @SuppressWarnings("unchecked")
    private String extractChatContent(Map<String, Object> raw) {
        Object choicesObj = raw.get("choices");
        if (!(choicesObj instanceof List)) return null;
        List<Object> choices = (List<Object>) choicesObj;
        if (choices.isEmpty() || !(choices.get(0) instanceof Map)) return null;
        Object messageObj = ((Map<String, Object>) choices.get(0)).get("message");
        if (!(messageObj instanceof Map)) return null;
        Object content = ((Map<String, Object>) messageObj).get("content");
        return content == null ? null : content.toString();
    }

    private String extractUrlFromText(String content) {
        if (content == null) return null;
        Matcher md = MARKDOWN_IMAGE_PATTERN.matcher(content);
        String last = null;
        while (md.find()) last = md.group(1);
        if (last != null) return last;
        Matcher data = DATA_URL_PATTERN.matcher(content);
        while (data.find()) last = data.group();
        if (last != null) return last;
        Matcher m = URL_PATTERN.matcher(content);
        while (m.find()) last = m.group();
        return last;
    }

    private String extractErrorMessage(String json) {
        try {
            Map<String, Object> m = objectMapper.readValue(json, MAP_TYPE);
            Object errObj = m.get("error");
            if (errObj instanceof Map) {
                Object msg = ((Map<?, ?>) errObj).get("message");
                if (msg != null) return msg.toString();
            }
        } catch (JsonProcessingException ignore) {
            // fall through
        }
        return json;
    }

    private String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    private MediaType mediaTypeOrDefault(String contentType) {
        if (contentType == null || contentType.trim().isEmpty()) {
            return MediaType.get("image/png");
        }
        MediaType parsed = MediaType.parse(contentType);
        return parsed != null ? parsed : MediaType.get("image/png");
    }

    private String safeFilename(String filename, String fallback) {
        if (filename == null || filename.trim().isEmpty()) {
            return fallback;
        }
        return filename.replace("\\", "_").replace("/", "_");
    }

    private static final class UpstreamCallResult {
        private final int status;
        private final String rawBody;

        private UpstreamCallResult(int status, String rawBody) {
            this.status = status;
            this.rawBody = rawBody;
        }
    }
}
