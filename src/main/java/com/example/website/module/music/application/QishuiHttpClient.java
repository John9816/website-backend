package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.OkHttpConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.*;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fixed upstream destinations; never accepts a caller supplied proxy URL. */
@Component
public class QishuiHttpClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QishuiHttpClient.class);
    static final String UA = "LunaOpenSDK/1.0.10";
    static final String PLATFORM = "https://open.douyin.com/api/luna/v1/platform";
    static final String OPEN = "https://open.qishui.com/api/luna/v1/platform";
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public QishuiHttpClient(
            @Qualifier(OkHttpConfig.CLIENT_MUSIC_THIRD_PARTY) OkHttpClient http,
            ObjectMapper mapper) {
        this.http =
                http.newBuilder()
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .retryOnConnectionFailure(false)
                        .build();
        this.mapper = mapper;
    }

    static Map<String, String> common(String deviceId) {
        Map<String, String> q = new LinkedHashMap<>();
        String[][] pairs = {
            {"aid", "765213"},
            {"app_id", "765213"},
            {"app_name", "byte_autoservice_lite"},
            {"version_code", "100010"},
            {"version_name", "1.0.10"},
            {"channel", "bydSong"},
            {"sdk_aid", "753999"},
            {"caller_name", "luna_open_sdk"},
            {"sdk_version_code", "100000100"},
            {"sdk_version_name", "0.0.1"},
            {"sdk_channel", "Test"},
            {"vehicle_type", "BYD"},
            {"device_brand", "BYD"},
            {"device_type", "BYD"},
            {"device_platform", "android"},
            {"resolution", "1920x1080"},
            {"os_version", "13"},
            {"ac", "wifi"}
        };
        for (String[] p : pairs) q.put(p[0], p[1]);
        for (String k : Arrays.asList("device_id", "did", "iid")) q.put(k, deviceId);
        q.put("vehicle_id", "qishui-" + deviceId);
        q.put("_timestamp", Long.toString(System.currentTimeMillis() / 1000));
        q.put(
                "_nonce",
                Integer.toString(java.util.concurrent.ThreadLocalRandom.current().nextInt(65536)));
        return q;
    }

    Result get(String endpoint, Map<String, String> query, Map<String, String> headers) {
        return send(endpoint, query, null, headers);
    }

    Result post(
            String endpoint, Map<String, String> query, Object body, Map<String, String> headers) {
        try {
            return send(
                    endpoint,
                    query,
                    RequestBody.create(
                            MediaType.parse("application/json; charset=utf-8"),
                            mapper.writeValueAsBytes(body)),
                    headers);
        } catch (IOException ex) {
            throw failure();
        }
    }

    Result form(
            String endpoint,
            Map<String, String> query,
            Map<String, String> body,
            Map<String, String> headers) {
        FormBody.Builder b = new FormBody.Builder();
        body.forEach(b::add);
        return send(endpoint, query, b.build(), headers);
    }

    private Result send(
            String endpoint,
            Map<String, String> query,
            RequestBody body,
            Map<String, String> headers) {
        MusicRequestBudget.ensureRemaining();
        HttpUrl.Builder url = Objects.requireNonNull(HttpUrl.parse(endpoint)).newBuilder();
        if (query != null) query.forEach(url::addQueryParameter);
        Request.Builder r = new Request.Builder().url(url.build()).header("User-Agent", UA);
        if (headers != null)
            headers.forEach(
                    (key, value) -> {
                        if (value == null || value.chars().anyMatch(c -> c < 32 || c > 126))
                            throw failure();
                        r.header(key, value);
                    });
        if (body != null) r.post(body);
        Call call = http.newCall(r.build());
        MusicRequestBudget.constrain(call, 5, TimeUnit.SECONDS);
        try (Response response = call.execute()) {
            MusicRequestBudget.ensureNotCancelled();
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Qishui HTTP failed path={} status={}", url.build().encodedPath(), response.code());
                throw failure();
            }
            okio.BufferedSource source = response.body().source();
            source.request(MAX_RESPONSE_BYTES + 1L);
            if (source.buffer().size() > MAX_RESPONSE_BYTES) throw failure();
            JsonNode json = mapper.readTree(source.readByteArray());
            if (json == null || !json.isObject()) throw failure();
            MusicRequestBudget.ensureRemaining();
            return new Result(json, response.headers());
        } catch (InterruptedIOException ex) {
            MusicRequestBudget.ensureNotCancelled();
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT);
        } catch (IOException | IllegalArgumentException ex) {
            MusicRequestBudget.ensureNotCancelled();
            throw failure();
        }
    }

    static JsonNode checked(JsonNode root) {
        checkCode(root, "err_no", "root");
        checkCode(root, "error_code", "root");
        JsonNode data = root.has("data") ? root.path("data") : root;
        checkCode(data, "error_code", "data");
        return data;
    }

    private static void checkCode(JsonNode node, String field, String location) {
        if (node.has(field) && node.path(field).asLong(-1) != 0) {
            // Only numeric status codes; never log upstream bodies, URLs, headers or credentials.
            log.warn("Qishui rejected response location={} field={} code={}", location, field, node.path(field).asLong(-1));
            throw failure();
        }
    }

    static MusicBusinessException failure() {
        return new MusicBusinessException(
                MusicErrorCode.UPSTREAM_SEARCH_FAILED, "Qishui upstream request failed");
    }

    static final class Result {
        final JsonNode json;
        final Headers headers;

        Result(JsonNode json, Headers headers) {
            this.json = json;
            this.headers = headers;
        }
    }
}
