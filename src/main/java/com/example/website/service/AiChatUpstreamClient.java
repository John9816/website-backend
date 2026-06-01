package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.config.OkHttpConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiChatUpstreamClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    @Qualifier(OkHttpConfig.CLIENT_AI_UPSTREAM)
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public ChatCompletionResult complete(String baseUrl,
                                         String apiKey,
                                         String model,
                                         List<ChatMessage> messages) {
        return complete(baseUrl, apiKey, model, messages, null);
    }

    public ChatCompletionResult complete(String baseUrl,
                                         String apiKey,
                                         String model,
                                         List<ChatMessage> messages,
                                         ChatAudioRequest audioRequest) {
        Request.Builder requestBuilder = new Request.Builder()
                .url(resolveChatCompletionsUrl(baseUrl))
                .header("Accept", "application/json")
                .post(RequestBody.create(writeJson(buildPayload(model, messages, audioRequest)), JSON));

        if (hasText(apiKey)) {
            requestBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }

        String rawBody;
        int status;
        try (Response response = okHttpClient.newCall(requestBuilder.build()).execute()) {
            status = response.code();
            ResponseBody body = response.body();
            rawBody = body == null ? "" : body.string();
        } catch (IOException e) {
            throw new BusinessException(502, "Upstream AI API I/O failed: " + e.getMessage());
        }

        if (!hasText(rawBody)) {
            throw new BusinessException(502, "Empty response from upstream AI API (status=" + status + ")");
        }

        Map<String, Object> raw = readJsonOrThrow(rawBody, status);
        throwIfUpstreamError(raw);
        if (status >= 400) {
            throw new BusinessException(502, "Upstream AI API returned " + status);
        }

        String content = extractAssistantContent(raw);
        ChatAudioResult audio = extractAssistantAudio(raw, audioRequest);
        if (!hasText(content)) {
            content = extractAssistantReasoningContent(raw);
        }
        if (!hasText(content) && audio == null) {
            throw new BusinessException(502, "Upstream returned no assistant content");
        }

        return new ChatCompletionResult(
                firstText(raw.get("model"), model),
                content == null ? "" : content,
                extractFinishReason(raw),
                extractUsageValue(raw, "prompt_tokens"),
                extractUsageValue(raw, "completion_tokens"),
                extractUsageValue(raw, "total_tokens"),
                audio
        );
    }

    public ChatAudioResult createSpeech(String baseUrl,
                                        String apiKey,
                                        String model,
                                        String input,
                                        String voice,
                                        String responseFormat,
                                        Double speed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", input);
        if (hasText(voice)) {
            payload.put("voice", voice.trim());
        }
        String normalizedFormat = normalizeSpeechFormat(responseFormat);
        payload.put("response_format", normalizedFormat);
        if (speed != null) {
            payload.put("speed", speed);
        }

        Request.Builder requestBuilder = new Request.Builder()
                .url(resolveAudioSpeechUrl(baseUrl))
                .header("Accept", "audio/*, application/json")
                .post(RequestBody.create(writeJson(payload), JSON));

        if (hasText(apiKey)) {
            requestBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }

        int status;
        String contentType;
        byte[] rawBytes;
        try (Response response = okHttpClient.newCall(requestBuilder.build()).execute()) {
            status = response.code();
            ResponseBody body = response.body();
            if (body == null) {
                throw new BusinessException(502, "Empty response from upstream speech API (status=" + status + ")");
            }
            contentType = body.contentType() == null ? null : body.contentType().toString();
            rawBytes = body.bytes();
        } catch (IOException e) {
            throw new BusinessException(502, "Upstream speech API I/O failed: " + e.getMessage());
        }

        if (rawBytes == null || rawBytes.length == 0) {
            throw new BusinessException(502, "Empty response from upstream speech API (status=" + status + ")");
        }
        if (status >= 400 || isJsonContentType(contentType)) {
            String rawText = new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> raw = tryReadJson(rawText);
            if (raw != null) {
                throwIfUpstreamError(raw);
            }
            if (status >= 400) {
                throw new BusinessException(502, "Upstream speech API returned " + status);
            }
            throw new BusinessException(502, "Upstream speech API returned JSON instead of audio");
        }

        return new ChatAudioResult(
                null,
                Base64.getEncoder().encodeToString(rawBytes),
                hasText(contentType) ? contentType : inferAudioMimeType(normalizedFormat, null),
                null,
                null
        );
    }

    private Map<String, Object> buildPayload(String model,
                                            List<ChatMessage> messages,
                                            ChatAudioRequest audioRequest) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("stream", false);
        if (audioRequest != null) {
            payload.put("audio", audioRequest.toMap());
        }
        return payload;
    }

    public okhttp3.Call streamComplete(String baseUrl,
                                       String apiKey,
                                       String model,
                                       List<ChatMessage> messages,
                                       StreamHandler handler) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("stream", true);
        Map<String, Object> streamOptions = new LinkedHashMap<>();
        streamOptions.put("include_usage", true);
        payload.put("stream_options", streamOptions);

        Request.Builder requestBuilder = new Request.Builder()
                .url(resolveChatCompletionsUrl(baseUrl))
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(writeJson(payload), JSON));
        if (hasText(apiKey)) {
            requestBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }

        okhttp3.Call call = okHttpClient.newCall(requestBuilder.build());
        try {
            Response response = call.execute();
            try {
                consumeStream(response, model, handler);
            } finally {
                response.close();
            }
        } catch (IOException e) {
            if (call.isCanceled()) {
                return call;
            }
            handler.onError(new BusinessException(502, "Upstream AI API I/O failed: " + e.getMessage()));
        } catch (BusinessException e) {
            handler.onError(e);
        } catch (RuntimeException e) {
            handler.onError(new BusinessException(502, "Upstream AI streaming failed: " + e.getMessage()));
        }
        return call;
    }

    private void consumeStream(Response response, String requestedModel, StreamHandler handler) throws IOException {
        int status = response.code();
        ResponseBody body = response.body();
        if (status >= 400) {
            String snippet = body == null ? "" : body.string();
            Map<String, Object> raw = tryReadJson(snippet);
            if (raw != null) {
                try {
                    throwIfUpstreamError(raw);
                } catch (BusinessException ex) {
                    handler.onError(ex);
                    return;
                }
            }
            handler.onError(new BusinessException(502, "Upstream AI API returned " + status));
            return;
        }
        if (body == null) {
            handler.onError(new BusinessException(502, "Empty streaming response from upstream AI API"));
            return;
        }

        BufferedSource source = body.source();
        StringBuilder accumulator = new StringBuilder();
        String resolvedModel = requestedModel;
        String finishReason = null;
        Integer promptTokens = null;
        Integer completionTokens = null;
        Integer totalTokens = null;
        boolean sawDone = false;

        while (!source.exhausted()) {
            String line = source.readUtf8Line();
            if (line == null) {
                break;
            }
            if (line.isEmpty() || line.startsWith(":")) {
                continue;
            }
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).trim();
            if (payload.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(payload)) {
                sawDone = true;
                break;
            }
            Map<String, Object> chunk = tryReadJson(payload);
            if (chunk == null) {
                continue;
            }
            try {
                throwIfUpstreamError(chunk);
            } catch (BusinessException ex) {
                handler.onError(ex);
                return;
            }
            Object modelField = chunk.get("model");
            if (modelField instanceof String && hasText((String) modelField)) {
                resolvedModel = ((String) modelField).trim();
            }
            String delta = extractDeltaContent(chunk);
            if (hasText(delta)) {
                accumulator.append(delta);
                handler.onDelta(delta);
            }
            String finish = extractDeltaFinishReason(chunk);
            if (hasText(finish)) {
                finishReason = finish;
            }
            Object usageObj = chunk.get("usage");
            if (usageObj instanceof Map) {
                Integer p = readUsageInt((Map<?, ?>) usageObj, "prompt_tokens");
                Integer c = readUsageInt((Map<?, ?>) usageObj, "completion_tokens");
                Integer t = readUsageInt((Map<?, ?>) usageObj, "total_tokens");
                if (p != null) promptTokens = p;
                if (c != null) completionTokens = c;
                if (t != null) totalTokens = t;
            }
        }

        if (!sawDone && accumulator.length() == 0) {
            handler.onError(new BusinessException(502, "Upstream returned no assistant content"));
            return;
        }
        handler.onUsage(promptTokens, completionTokens, totalTokens);
        handler.onFinish(finishReason, accumulator.toString(), resolvedModel);
    }

    @SuppressWarnings("unchecked")
    private String extractDeltaContent(Map<String, Object> chunk) {
        Object choicesObj = chunk.get("choices");
        if (!(choicesObj instanceof List)) {
            return null;
        }
        List<?> choices = (List<?>) choicesObj;
        if (choices.isEmpty() || !(choices.get(0) instanceof Map)) {
            return null;
        }
        Object deltaObj = ((Map<String, Object>) choices.get(0)).get("delta");
        if (!(deltaObj instanceof Map)) {
            return null;
        }
        Object content = ((Map<String, Object>) deltaObj).get("content");
        return normalizeContent(content);
    }

    @SuppressWarnings("unchecked")
    private String extractDeltaFinishReason(Map<String, Object> chunk) {
        Object choicesObj = chunk.get("choices");
        if (!(choicesObj instanceof List)) {
            return null;
        }
        List<?> choices = (List<?>) choicesObj;
        if (choices.isEmpty() || !(choices.get(0) instanceof Map)) {
            return null;
        }
        Object finish = ((Map<String, Object>) choices.get(0)).get("finish_reason");
        return finish instanceof String ? ((String) finish).trim() : null;
    }

    private Integer readUsageInt(Map<?, ?> usage, String key) {
        Object value = usage.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private Map<String, Object> tryReadJson(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(text, MAP_TYPE);
        } catch (JsonProcessingException ignore) {
            return null;
        }
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "Failed to serialize AI request: " + e.getOriginalMessage());
        }
    }

    private Map<String, Object> readJsonOrThrow(String rawBody, int status) {
        try {
            return objectMapper.readValue(rawBody, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new BusinessException(502, "Invalid JSON from upstream AI API (status=" + status + ")");
        }
    }

    private void throwIfUpstreamError(Map<String, Object> raw) {
        Object errorObj = raw.get("error");
        if (!(errorObj instanceof Map)) {
            return;
        }
        Map<?, ?> error = (Map<?, ?>) errorObj;
        String message = firstText(error.get("message"), error.get("code"), "Unknown upstream error");
        String detail = firstText(error.get("param"), null);
        if (hasText(detail)) {
            message = message + " (" + detail + ")";
        }
        throw new BusinessException(502, "Upstream error: " + message);
    }

    @SuppressWarnings("unchecked")
    private String extractAssistantContent(Map<String, Object> raw) {
        Object messageObj = extractAssistantMessageObject(raw);
        if (!(messageObj instanceof Map)) {
            return null;
        }
        Object contentObj = ((Map<String, Object>) messageObj).get("content");
        return normalizeContent(contentObj);
    }

    @SuppressWarnings("unchecked")
    private String extractAssistantReasoningContent(Map<String, Object> raw) {
        Object messageObj = extractAssistantMessageObject(raw);
        if (!(messageObj instanceof Map)) {
            return null;
        }
        return firstText(((Map<String, Object>) messageObj).get("reasoning_content"), null);
    }

    @SuppressWarnings("unchecked")
    private ChatAudioResult extractAssistantAudio(Map<String, Object> raw, ChatAudioRequest audioRequest) {
        Object messageObj = extractAssistantMessageObject(raw);
        if (!(messageObj instanceof Map)) {
            return null;
        }
        Object audioObj = ((Map<String, Object>) messageObj).get("audio");
        if (!(audioObj instanceof Map)) {
            return null;
        }
        Map<String, Object> audio = (Map<String, Object>) audioObj;
        String data = firstText(audio.get("data"), null);
        if (!hasText(data)) {
            return null;
        }
        String mimeType = inferAudioMimeType(
                audioRequest == null ? null : audioRequest.getFormat(),
                data
        );
        Long expiresAt = null;
        Object expiresAtObj = audio.get("expires_at");
        if (expiresAtObj instanceof Number) {
            expiresAt = ((Number) expiresAtObj).longValue();
        } else if (expiresAtObj instanceof String) {
            try {
                expiresAt = Long.parseLong(((String) expiresAtObj).trim());
            } catch (NumberFormatException ignore) {
                expiresAt = null;
            }
        }
        return new ChatAudioResult(
                firstText(audio.get("id"), null),
                data,
                mimeType,
                expiresAt,
                firstText(audio.get("transcript"), null)
        );
    }

    @SuppressWarnings("unchecked")
    private String extractFinishReason(Map<String, Object> raw) {
        Object choicesObj = raw.get("choices");
        if (!(choicesObj instanceof List)) {
            return null;
        }
        List<?> choices = (List<?>) choicesObj;
        if (choices.isEmpty() || !(choices.get(0) instanceof Map)) {
            return null;
        }
        return firstText(((Map<String, Object>) choices.get(0)).get("finish_reason"), null);
    }

    @SuppressWarnings("unchecked")
    private Integer extractUsageValue(Map<String, Object> raw, String key) {
        Object usageObj = raw.get("usage");
        if (!(usageObj instanceof Map)) {
            return null;
        }
        Object value = ((Map<String, Object>) usageObj).get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String normalizeContent(Object contentObj) {
        if (contentObj instanceof String) {
            return ((String) contentObj).trim();
        }
        if (!(contentObj instanceof List)) {
            return contentObj == null ? null : contentObj.toString();
        }
        StringBuilder builder = new StringBuilder();
        for (Object item : (List<Object>) contentObj) {
            String text = null;
            if (item instanceof String) {
                text = item.toString();
            } else if (item instanceof Map) {
                Map<String, Object> part = (Map<String, Object>) item;
                text = firstText(part.get("text"), part.get("content"));
            }
            if (hasText(text)) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(text.trim());
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    @SuppressWarnings("unchecked")
    private Object extractAssistantMessageObject(Map<String, Object> raw) {
        Object choicesObj = raw.get("choices");
        if (!(choicesObj instanceof List)) {
            return null;
        }
        List<?> choices = (List<?>) choicesObj;
        if (choices.isEmpty() || !(choices.get(0) instanceof Map)) {
            return null;
        }
        return ((Map<String, Object>) choices.get(0)).get("message");
    }

    private String resolveChatCompletionsUrl(String baseUrl) {
        if (!hasText(baseUrl)) {
            throw new BusinessException(500, "System config missing: ai.chat.baseUrl");
        }
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        if (normalized.endsWith("/")) {
            return normalized + "chat/completions";
        }
        return normalized + "/chat/completions";
    }

    private String resolveAudioSpeechUrl(String baseUrl) {
        if (!hasText(baseUrl)) {
            throw new BusinessException(500, "System config missing: ai.chat.baseUrl");
        }
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/audio/speech")) {
            return normalized;
        }
        if (normalized.endsWith("/chat/completions")) {
            return normalized.substring(0, normalized.length() - "/chat/completions".length()) + "/audio/speech";
        }
        if (normalized.endsWith("/")) {
            return normalized + "audio/speech";
        }
        return normalized + "/audio/speech";
    }

    private String firstText(Object primary, Object fallback) {
        return firstText(primary, fallback, null);
    }

    private String firstText(Object primary, Object fallback, String defaultValue) {
        if (primary instanceof String && hasText((String) primary)) {
            return ((String) primary).trim();
        }
        if (primary != null && !(primary instanceof String)) {
            return primary.toString();
        }
        if (fallback instanceof String && hasText((String) fallback)) {
            return ((String) fallback).trim();
        }
        if (fallback != null && !(fallback instanceof String)) {
            return fallback.toString();
        }
        return defaultValue;
    }

    private boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
    }

    private String inferAudioMimeType(String format, String base64Data) {
        String normalized = format == null ? null : format.trim().toLowerCase(Locale.ROOT);
        if ("wav".equals(normalized)) {
            return "audio/wav";
        }
        if ("mp3".equals(normalized)) {
            return "audio/mpeg";
        }
        if ("flac".equals(normalized)) {
            return "audio/flac";
        }
        if ("ogg".equals(normalized) || "opus".equals(normalized)) {
            return "audio/ogg";
        }
        if (base64Data != null && base64Data.startsWith("UklGR")) {
            return "audio/wav";
        }
        return "application/octet-stream";
    }

    private String normalizeSpeechFormat(String format) {
        String normalized = format == null ? null : format.trim().toLowerCase(Locale.ROOT);
        if (!hasText(normalized)) {
            return "mp3";
        }
        return normalized;
    }

    private boolean isJsonContentType(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json");
    }

    @Data
    @AllArgsConstructor
    public static class ChatMessage {
        private String role;
        private Object content;
    }

    public interface StreamHandler {
        void onDelta(String contentDelta);
        void onUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens);
        void onFinish(String finishReason, String fullContent, String resolvedModel);
        void onError(BusinessException ex);
    }

    @Data
    @AllArgsConstructor
    public static class ChatAudioRequest {
        private String format;
        private String voice;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (format != null && !format.trim().isEmpty()) {
                map.put("format", format.trim());
            }
            if (voice != null && !voice.trim().isEmpty()) {
                map.put("voice", voice.trim());
            }
            return map;
        }
    }

    @Data
    @AllArgsConstructor
    public static class ChatAudioResult {
        private String id;
        private String data;
        private String mimeType;
        private Long expiresAt;
        private String transcript;
    }

    @Data
    @AllArgsConstructor
    public static class ChatCompletionResult {
        private String model;
        private String content;
        private String finishReason;
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
        private ChatAudioResult audio;
    }
}
