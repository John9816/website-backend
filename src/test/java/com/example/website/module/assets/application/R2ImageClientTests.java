package com.example.website.module.assets.application;

import com.example.website.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class R2ImageClientTests {

    private static final String OK_JSON =
            "{\"code\":0,\"message\":\"ok\",\"data\":{\"url\":\"https://km.751152.xyz/editor/media/images/x.png\",\"source\":\"r2\"}}";

    private R2ImageClient clientWith(String responseJson, int status, AtomicReference<Request> captured) {
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            captured.set(chain.request());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("test")
                    .body(ResponseBody.create(responseJson, MediaType.parse("application/json"))).build();
        }).build();
        return new R2ImageClient(http, new ObjectMapper(),
                "https://km.751152.xyz/editor/api/upload", "secret-token");
    }

    @Test
    void postsDataUrlWithBearerAndReturnsExternalUrl() throws IOException {
        AtomicReference<Request> req = new AtomicReference<>();
        R2ImageClient client = clientWith(OK_JSON, 200, req);

        String url = client.upload("dGVzdA==", "image/png");

        assertEquals("https://km.751152.xyz/editor/media/images/x.png", url);
        assertEquals("POST", req.get().method());
        assertEquals("Bearer secret-token", req.get().header("Authorization"));
        Buffer buf = new Buffer();
        req.get().body().writeTo(buf);
        String body = buf.readUtf8();
        assertTrue(body.contains("data:image/png;base64,dGVzdA=="), "payload should carry dataUrl: " + body);
    }

    @Test
    void rejectsNonZeroCodeOrMissingUrl() {
        AtomicReference<Request> req = new AtomicReference<>();
        R2ImageClient client = clientWith("{\"code\":1,\"data\":{}}", 200, req);
        assertEquals(502, assertThrows(BusinessException.class,
                () -> client.upload("dGVzdA==", "image/png")).getCode());
    }

    @Test
    void rejectsHttpErrors() {
        AtomicReference<Request> req = new AtomicReference<>();
        R2ImageClient client = clientWith("oops", 503, req);
        assertEquals(502, assertThrows(BusinessException.class,
                () -> client.upload("dGVzdA==", "image/png")).getCode());
    }
}
