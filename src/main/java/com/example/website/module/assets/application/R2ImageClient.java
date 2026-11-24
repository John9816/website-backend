package com.example.website.module.assets.application;

import com.example.website.common.BusinessException;
import com.example.website.config.OkHttpConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;

/**
 * 自建 CF R2 图床客户端。
 * 把图片以 dataUrl 形式 POST 到知识库 worker 的 /editor/api/upload，
 * 由 worker 直接写入 R2 bucket（ENV.BUCKET.put），返回匿名可读的 media 外链。
 * 用于替代原第三方图床（api.xunhuisi.store），后端与编辑器共用同一存储通道。
 */
@Service
public class R2ImageClient {

    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String uploadUrl;
    private final String token;

    public R2ImageClient(
            @Qualifier(OkHttpConfig.CLIENT_IMAGE_UPSTREAM) OkHttpClient httpClient,
            ObjectMapper objectMapper,
            @Value("${app.storage.r2-image-upload-url:https://km.751152.xyz/editor/api/upload}") String uploadUrl,
            @Value("${app.storage.r2-image-token:}") String token) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.uploadUrl = uploadUrl;
        this.token = token;
    }

    /** 上传 base64 图片（mime 形如 image/png），返回公网外链。 */
    public String upload(String base64, String mime) {
        if (HttpUrl.parse(uploadUrl) == null) {
            throw new BusinessException(500, "Invalid image upload endpoint");
        }
        String safeMime = (mime == null || mime.isBlank()) ? "image/png" : mime;
        String payload = "{\"dataUrl\":\"data:" + safeMime + ";base64," + base64 + "\"}";
        Request.Builder rb = new Request.Builder().url(uploadUrl)
                .post(RequestBody.create(payload, JSON));
        if (token != null && !token.isBlank()) {
            rb.header("Authorization", "Bearer " + token);
        }
        try (Response response = httpClient.newCall(rb.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new BusinessException(502, "R2 image upload failed: HTTP " + response.code());
            }
            String raw = response.body().string();
            JsonNode root = objectMapper.readTree(raw);
            int code = (root != null && root.path("code").isIntegralNumber()) ? root.path("code").asInt() : -1;
            String url = (root == null) ? "" : root.path("data").path("url").asText("").trim();
            if (code != 0 || url.isEmpty() || !isHttpUrl(url)) {
                throw new BusinessException(502, "R2 image upload returned an invalid result");
            }
            return url;
        } catch (IOException e) {
            throw new BusinessException(502, "R2 image upload failed or returned invalid JSON");
        }
    }

    /** 下载远程图片字节并转存到 R2，返回公网外链（用于文章/AI生图的远程 URL 转存）。 */
    public String uploadFromUrl(String imageUrl) {
        if (!isHttpUrl(imageUrl)) {
            throw new BusinessException(400, "Invalid image URL");
        }
        try {
            Request req = new Request.Builder().url(imageUrl).build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    throw new BusinessException(502, "Download image failed: HTTP " + resp.code());
                }
                byte[] bytes = resp.body().bytes();
                if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                    throw new BusinessException(413, "Image is empty or too large");
                }
                String mime = resp.header("Content-Type");
                if (mime == null || !mime.toLowerCase().startsWith("image/")) mime = "image/png";
                int semi = mime.indexOf(';');
                if (semi > 0) mime = mime.substring(0, semi);
                return upload(Base64.getEncoder().encodeToString(bytes), mime.trim());
            }
        } catch (IOException e) {
            throw new BusinessException(502, "Download image failed");
        }
    }

    private static boolean isHttpUrl(String value) {
        HttpUrl url = value == null ? null : HttpUrl.parse(value);
        return url != null && url.username().isEmpty() && url.password().isEmpty();
    }
}
