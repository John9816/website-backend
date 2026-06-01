package com.example.website.service;

import com.example.website.config.OkHttpConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class GoFileClient {

    private static final MediaType IMAGE_PNG = MediaType.parse("image/png");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public GoFileClient(@Qualifier(OkHttpConfig.CLIENT_IMAGE_UPSTREAM) OkHttpClient okHttpClient,
                        ObjectMapper objectMapper) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * @param baseUrl  go-file server base URL, e.g. "https://file.example.com"
     * @param token    API token (can be null if upload permission is open)
     * @param data     raw image bytes
     * @param filename suggested filename, e.g. "abc123.png"
     * @return public URL of the uploaded image, or empty if upload failed
     */
    public Optional<String> upload(String baseUrl, String token, byte[] data, String filename) {
        String url = baseUrl.replaceAll("/+$", "") + "/api/image";

        RequestBody fileBody = RequestBody.create(data, IMAGE_PNG);
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", filename, fileBody)
                .build();

        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .post(body);
        if (token != null && !token.trim().isEmpty()) {
            reqBuilder.header("Authorization", token.trim());
        }

        try (Response resp = okHttpClient.newCall(reqBuilder.build()).execute()) {
            if (!resp.isSuccessful()) {
                log.warn("go-file upload returned HTTP {} for {}", resp.code(), url);
                return Optional.empty();
            }
            String raw = resp.body() != null ? resp.body().string() : "";
            Map<String, Object> json = objectMapper.readValue(raw, MAP_TYPE);
            Object success = json.get("success");
            if (!(success instanceof Boolean) || !(Boolean) success) {
                Object msg = json.get("message");
                log.warn("go-file upload failed: {}", msg);
                return Optional.empty();
            }
            Object dataObj = json.get("data");
            if (dataObj instanceof List) {
                List<?> list = (List<?>) dataObj;
                if (!list.isEmpty() && list.get(0) != null) {
                    String uploadedName = list.get(0).toString();
                    return Optional.of(baseUrl.replaceAll("/+$", "") + "/image/" + uploadedName);
                }
            }
            log.warn("go-file upload response had no data array: {}", raw);
            return Optional.empty();
        } catch (IOException e) {
            log.error("go-file upload I/O error: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public byte[] downloadBytes(String url) {
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = okHttpClient.newCall(req).execute()) {
            if (resp.isSuccessful() && resp.body() != null) {
                return resp.body().bytes();
            }
            log.warn("Failed to download {}: HTTP {}", url, resp.code());
            return null;
        } catch (IOException e) {
            log.warn("Failed to download {}: {}", url, e.getMessage());
            return null;
        }
    }
}
