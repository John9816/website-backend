package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.config.OkHttpConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class WechatOfficialAccountClient {

    public static final String CFG_APP_ID = "wechat.appId";
    public static final String CFG_APP_SECRET = "wechat.appSecret";
    public static final String CFG_AUTHOR = "wechat.author";
    public static final String CFG_SOURCE_URL = "wechat.contentSourceUrl";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType IMAGE = MediaType.get("image/png");
    private static final String API_BASE = "https://api.weixin.qq.com";

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    private volatile TokenCache tokenCache;

    public WechatOfficialAccountClient(@Qualifier(OkHttpConfig.CLIENT_QUICK) OkHttpClient okHttpClient,
                                       ObjectMapper objectMapper) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    public boolean configured(String appId, String appSecret) {
        return hasText(appId) && hasText(appSecret);
    }

    public MaterialResult uploadPermanentImage(String appId, String appSecret, byte[] data, String filename) {
        String token = accessToken(appId, appSecret);
        HttpUrl url = HttpUrl.parse(API_BASE + "/cgi-bin/material/add_material")
                .newBuilder()
                .addQueryParameter("access_token", token)
                .addQueryParameter("type", "image")
                .build();
        RequestBody fileBody = RequestBody.create(data, IMAGE);
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("media", defaultText(filename, "cover.png"), fileBody)
                .build();
        Map<String, Object> json = post(url.toString(), body);
        assertWechatOk(json, "上传微信封面素材失败");
        String mediaId = asString(json.get("media_id"));
        if (!hasText(mediaId)) {
            throw new BusinessException(502, "上传微信封面素材失败：未返回 media_id");
        }
        return new MaterialResult(mediaId, asString(json.get("url")));
    }

    public DraftResult addDraft(String appId,
                                String appSecret,
                                String title,
                                String digest,
                                String html,
                                String thumbMediaId,
                                String author,
                                String contentSourceUrl) {
        if (!hasText(thumbMediaId)) {
            throw new BusinessException(400, "请先配置 wechat.coverMediaId 或为文章设置封面图");
        }
        String token = accessToken(appId, appSecret);
        HttpUrl url = HttpUrl.parse(API_BASE + "/cgi-bin/draft/add")
                .newBuilder()
                .addQueryParameter("access_token", token)
                .build();
        Map<String, Object> article = new LinkedHashMap<>();
        article.put("title", truncate(defaultText(title, "未命名文章"), 64));
        article.put("author", truncate(defaultText(author, ""), 8));
        article.put("digest", truncate(defaultText(digest, ""), 120));
        article.put("content", defaultText(html, "<p></p>"));
        article.put("content_source_url", defaultText(contentSourceUrl, ""));
        article.put("thumb_media_id", thumbMediaId);
        article.put("need_open_comment", 0);
        article.put("only_fans_can_comment", 0);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("articles", Collections.singletonList(article));
        Map<String, Object> json = postJson(url.toString(), payload);
        assertWechatOk(json, "创建微信草稿失败");
        String mediaId = asString(json.get("media_id"));
        if (!hasText(mediaId)) {
            throw new BusinessException(502, "创建微信草稿失败：未返回 media_id");
        }
        return new DraftResult(mediaId);
    }

    public PublishResult submitPublish(String appId, String appSecret, String mediaId) {
        if (!hasText(mediaId) || mediaId.startsWith("local-")) {
            throw new BusinessException(400, "缺少可发布的微信草稿 media_id");
        }
        String token = accessToken(appId, appSecret);
        HttpUrl url = HttpUrl.parse(API_BASE + "/cgi-bin/freepublish/submit")
                .newBuilder()
                .addQueryParameter("access_token", token)
                .build();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("media_id", mediaId);
        Map<String, Object> json = postJson(url.toString(), payload);
        assertWechatOk(json, "提交微信发布失败");
        String publishId = asString(json.get("publish_id"));
        if (!hasText(publishId)) {
            throw new BusinessException(502, "提交微信发布失败：未返回 publish_id");
        }
        return new PublishResult(publishId);
    }

    private String accessToken(String appId, String appSecret) {
        if (!configured(appId, appSecret)) {
            throw new BusinessException(500, "System config missing: wechat.appId / wechat.appSecret");
        }
        long now = System.currentTimeMillis();
        TokenCache cached = tokenCache;
        if (cached != null && cached.matches(appId, appSecret) && cached.expiresAtMs - 60_000L > now) {
            return cached.token;
        }
        synchronized (this) {
            cached = tokenCache;
            now = System.currentTimeMillis();
            if (cached != null && cached.matches(appId, appSecret) && cached.expiresAtMs - 60_000L > now) {
                return cached.token;
            }
            HttpUrl url = HttpUrl.parse(API_BASE + "/cgi-bin/token")
                    .newBuilder()
                    .addQueryParameter("grant_type", "client_credential")
                    .addQueryParameter("appid", appId)
                    .addQueryParameter("secret", appSecret)
                    .build();
            Map<String, Object> json = get(url.toString());
            assertWechatOk(json, "获取微信 access_token 失败");
            String token = asString(json.get("access_token"));
            if (!hasText(token)) {
                throw new BusinessException(502, "获取微信 access_token 失败：未返回 access_token");
            }
            long expiresIn = asLong(json.get("expires_in"), 7200L);
            tokenCache = new TokenCache(appId, appSecret, token, System.currentTimeMillis() + Math.max(60L, expiresIn) * 1000L);
            return token;
        }
    }

    private Map<String, Object> get(String url) {
        Request request = new Request.Builder().url(url).get().build();
        return execute(request);
    }

    private Map<String, Object> postJson(String url, Object payload) {
        try {
            return post(url, RequestBody.create(objectMapper.writeValueAsString(payload), JSON));
        } catch (IOException e) {
            throw new BusinessException(500, "序列化微信请求失败");
        }
    }

    private Map<String, Object> post(String url, RequestBody body) {
        Request request = new Request.Builder().url(url).post(body).build();
        return execute(request);
    }

    private Map<String, Object> execute(Request request) {
        try (Response response = okHttpClient.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new BusinessException(502, "微信接口 HTTP " + response.code());
            }
            if (!hasText(raw)) {
                return Collections.emptyMap();
            }
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "微信接口调用失败：" + e.getMessage());
        }
    }

    private void assertWechatOk(Map<String, Object> json, String prefix) {
        int errcode = asInt(json.get("errcode"), 0);
        if (errcode != 0) {
            String errmsg = asString(json.get("errmsg"));
            throw new BusinessException(502, prefix + "（" + errcode + (hasText(errmsg) ? "：" + errmsg : "") + "）");
        }
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private String defaultText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private long asLong(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    @Data
    @AllArgsConstructor
    private static class TokenCache {
        private String appId;
        private String appSecret;
        private String token;
        private long expiresAtMs;

        boolean matches(String otherAppId, String otherAppSecret) {
            return appId.equals(otherAppId) && appSecret.equals(otherAppSecret);
        }
    }

    @Data
    @AllArgsConstructor
    public static class MaterialResult {
        private String mediaId;
        private String url;
    }

    @Data
    @AllArgsConstructor
    public static class DraftResult {
        private String mediaId;
    }

    @Data
    @AllArgsConstructor
    public static class PublishResult {
        private String publishId;
    }
}
