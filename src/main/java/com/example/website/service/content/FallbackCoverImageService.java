package com.example.website.service.content;

import com.example.website.config.OkHttpConfig;
import com.example.website.service.SysConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.website.service.content.ContentTextUtils.defaultText;
import static com.example.website.service.content.ContentTextUtils.hasText;
import static com.example.website.service.content.ContentTextUtils.trimToNull;

/**
 * Keyless fallback for cover-image generation. When the configured primary image API
 * ({@code image.api.*}) is unset or fails, the content factory falls back to this free
 * SSE-based service so a draft still gets a cover instead of none.
 *
 * <p>The endpoint streams Server-Sent Events: {@code start} → {@code ping}… → {@code generating}
 * (whose {@code data.result} carries a Markdown image link) → {@code end}. This differs from the
 * OpenAI-style {@code choices[].delta.content} shape {@code ImageService} already handles, so the
 * SSE is parsed here directly. Best-effort: any failure returns {@code null} and the caller
 * degrades to no cover.
 *
 * <p>All knobs live in {@code sys_config}, read at call time:
 * <ul>
 *   <li>{@code content.cover.fallback.enabled} — master switch (default true)</li>
 *   <li>{@code content.cover.fallback.url} — SSE endpoint</li>
 *   <li>{@code content.cover.fallback.model} / {@code .ratio} / {@code .resolution} — request params</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FallbackCoverImageService {

    public static final String CFG_ENABLED = "content.cover.fallback.enabled";
    public static final String CFG_URL = "content.cover.fallback.url";
    public static final String CFG_MODEL = "content.cover.fallback.model";
    public static final String CFG_RATIO = "content.cover.fallback.ratio";
    public static final String CFG_RESOLUTION = "content.cover.fallback.resolution";

    private static final String DEFAULT_URL = "https://img.regenin.online/api/chat";
    private static final String DEFAULT_MODEL = "GPT Image 2.0";
    private static final String DEFAULT_RATIO = "16:9";
    private static final String DEFAULT_RESOLUTION = "2K";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Pattern IMAGE_URL_PATTERN =
            Pattern.compile("https?://[^\\s)\"'>]+\\.(?:png|jpe?g|webp|gif)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_URL_PATTERN = Pattern.compile("https?://[^\\s)\"'>]+");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    private final SysConfigService configService;
    private final ObjectMapper objectMapper;
    // Reuse the image upstream client: generous read/call timeouts (this endpoint took ~45s).
    @Qualifier(OkHttpConfig.CLIENT_IMAGE_UPSTREAM)
    private final OkHttpClient okHttpClient;

    /** Whether the fallback is enabled; default on so drafts still get a cover without a key. */
    public boolean enabled() {
        return !"false".equalsIgnoreCase(config(CFG_ENABLED));
    }

    /** Model label used for persisted history when this fallback produced the image. */
    public String model() {
        return defaultText(config(CFG_MODEL), DEFAULT_MODEL);
    }

    /**
     * Generate a cover image for {@code prompt} via the free service. Returns the image URL, or
     * {@code null} when disabled, on any error, or when no image URL is found in the stream.
     */
    public String generate(String prompt) {
        String text = trimToNull(prompt);
        if (text == null || !enabled()) {
            return null;
        }
        try {
            Request request = new Request.Builder()
                    .url(defaultText(config(CFG_URL), DEFAULT_URL))
                    .header("Accept", "*/*")
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(buildBody(text), JSON))
                    .build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    log.warn("[cover-fallback] HTTP {}", response.code());
                    return null;
                }
                return extractImageUrl(body.string());
            }
        } catch (IOException e) {
            log.warn("[cover-fallback] I/O failed: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[cover-fallback] failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildBody(String prompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", prompt);
        payload.put("type", "image");
        payload.put("model", defaultText(config(CFG_MODEL), DEFAULT_MODEL));
        payload.put("ratio", defaultText(config(CFG_RATIO), DEFAULT_RATIO));
        payload.put("resolution", defaultText(config(CFG_RESOLUTION), DEFAULT_RESOLUTION));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // Prompt is plain text; serialization should never fail. Fall back to a hand-built body.
            return "{\"type\":\"image\"}";
        }
    }

    /**
     * Walk the SSE stream and return the last image URL found. The image arrives in a
     * {@code generating} event as {@code data.result} holding a Markdown image link; we also
     * tolerate a plain URL. Later events win so a final/replacement URL is preferred.
     */
    String extractImageUrl(String sseBody) {
        if (!hasText(sseBody)) {
            return null;
        }
        String found = null;
        for (String line : sseBody.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String payload = trimmed.substring("data:".length()).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            String result = readResult(payload);
            if (result == null) {
                continue;
            }
            String url = firstImageUrl(result);
            if (url != null) {
                found = url;
            }
        }
        return found;
    }

    /** Pull {@code data.result} (or a top-level {@code result}) from one SSE JSON payload. */
    private String readResult(String payload) {
        Map<String, Object> chunk;
        try {
            chunk = objectMapper.readValue(payload, MAP_TYPE);
        } catch (Exception e) {
            return null;
        }
        Object data = chunk.get("data");
        if (data instanceof Map) {
            Object result = ((Map<?, ?>) data).get("result");
            if (result != null) {
                return result.toString();
            }
        }
        Object topResult = chunk.get("result");
        return topResult == null ? null : topResult.toString();
    }

    /** Prefer a URL ending in an image extension; otherwise accept any http(s) URL. */
    private String firstImageUrl(String text) {
        Matcher image = IMAGE_URL_PATTERN.matcher(text);
        String last = null;
        while (image.find()) {
            last = image.group();
        }
        if (last != null) {
            return last;
        }
        Matcher any = ANY_URL_PATTERN.matcher(text);
        while (any.find()) {
            last = any.group();
        }
        return last;
    }

    private String config(String key) {
        return configService.getValue(key).orElse("");
    }
}
