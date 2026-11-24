package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.module.music.api.dto.MusicQuality;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.PlayInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TuneFreeV1PlayClientTests {

    private static final String TOKEN = "test-v1-token";

    @ParameterizedTest
    @CsvSource({
            "QQ, qq",
            "NETEASE, wy",
            "KUWO, kw"
    })
    void mapsSourceIdentifiers(MusicSource source, String expected) {
        assertEquals(expected, TuneFreeV1PlayClient.v1Source(source));
    }

    @Test
    void qishuiHasNoV1Mapping() {
        assertThrows(MusicBusinessException.class, () -> TuneFreeV1PlayClient.v1Source(MusicSource.QISHUI));
    }

    @Test
    void sendsCorrectRequestBodyAndBearer() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        TuneFreeV1AuthService authSvc = authService();
        TuneFreeV1PlayClient client = client(authSvc, chain -> {
            body.set(readBody(chain));
            auth.set(chain.request().header("Authorization"));
            path.set(chain.request().url().encodedPath());
            return response(chain, 201, successJson("320k", "320k"), null);
        });

        PlayInfo info = client.play(MusicSource.QQ, "0048xT0Q2Rb9rM", MusicQuality.K320);

        assertEquals("/v1/parse", path.get());
        assertEquals("Bearer " + TOKEN, auth.get());
        JsonNode req = new ObjectMapper().readTree(body.get());
        assertEquals("qq", req.path("source").asText());
        assertEquals("0048xT0Q2Rb9rM", req.path("trackId").asText());
        assertEquals("320k", req.path("quality").asText());
        assertEquals("https://cdn.example.com/song.m4a", info.getPlayUrl());
        assertEquals("320k", info.getActualQuality());
        assertEquals("320k", info.getRequestedQuality());
    }

    @Test
    void neteaseUsesWyAndAccepts201WithDegradedQuality() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        TuneFreeV1PlayClient client = client(authService(), chain -> {
            body.set(readBody(chain));
            return response(chain, 201, successJson("320k", "128k"), null);
        });

        PlayInfo info = client.play(MusicSource.NETEASE, "5257138", MusicQuality.K320);

        assertEquals("wy", new ObjectMapper().readTree(body.get()).path("source").asText());
        assertEquals("128k", info.getActualQuality());
    }

    @Test
    void refreshesTokenOnceOn401() throws Exception {
        AtomicInteger httpCall = new AtomicInteger();
        TuneFreeV1AuthService authSvc = mock(TuneFreeV1AuthService.class);
        when(authSvc.getToken()).thenReturn(TOKEN, "fresh-token");
        TuneFreeV1PlayClient client = client(authSvc, chain -> {
            if (httpCall.getAndIncrement() == 0) return response(chain, 401, "{\"success\":false}", null);
            return response(chain, 201, successJson("320k", "320k"), null);
        });

        PlayInfo info = client.play(MusicSource.QQ, "id1", MusicQuality.K320);

        assertNotNull(info.getPlayUrl());
        verify(authSvc, times(1)).invalidate();
        verify(authSvc, times(2)).getToken();
    }

    @Test
    void contentUnavailableThrowsNoPlayableUrl() {
        TuneFreeV1PlayClient client = client(authService(), chain -> response(chain, 422,
                "{\"success\":false,\"error\":{\"code\":\"PARSE_CONTENT_UNAVAILABLE\"}}", null));
        MusicBusinessException ex = assertThrows(MusicBusinessException.class,
                () -> client.play(MusicSource.QQ, "id2", MusicQuality.K320));
        assertEquals(com.example.website.common.MusicErrorCode.NO_PLAYABLE_URL, ex.getErrorCode());
    }

    @Test
    void parsesExpireIso() {
        String near = java.time.OffsetDateTime.now().plusHours(1).format(
                java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Integer sec = TuneFreeV1PlayClient.parseExpire(near);
        assertNotNull(sec);
        org.junit.jupiter.api.Assertions.assertTrue(sec > 0 && sec <= 3600);
        assertNull(TuneFreeV1PlayClient.parseExpire("not-a-date"));
        assertNull(TuneFreeV1PlayClient.parseExpire(null));
    }

    // ---- helpers ----

    private static TuneFreeV1AuthService authService() {
        TuneFreeV1AuthService svc = mock(TuneFreeV1AuthService.class);
        when(svc.getToken()).thenReturn(TOKEN);
        return svc;
    }

    private static TuneFreeV1PlayClient client(TuneFreeV1AuthService auth, Interceptor interceptor) {
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(interceptor).build();
        return new TuneFreeV1PlayClient(auth, http, new ObjectMapper());
    }

    private static String successJson(String requested, String actual) {
        return "{\"success\":true,\"data\":{"
                + "\"playUrl\":\"https://cdn.example.com/song.m4a\","
                + "\"expiresAt\":\"2099-01-01T00:00:00+08:00\","
                + "\"requestedQuality\":\"" + requested + "\","
                + "\"actualQuality\":\"" + actual + "\","
                + "\"cacheHit\":false},\"requestId\":\"test\"}";
    }

    private static String readBody(Interceptor.Chain chain) throws IOException {
        return chain.request().body() == null ? "" : bodyToString(chain.request().body());
    }

    private static String bodyToString(okhttp3.RequestBody body) throws IOException {
        okio.Buffer buffer = new okio.Buffer();
        body.writeTo(buffer);
        return buffer.readUtf8();
    }

    private static Response response(Interceptor.Chain chain, int status, String body, String retryAfter)
            throws IOException {
        Response.Builder b = new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(status >= 200 && status < 300 ? "OK" : "Error")
                .body(ResponseBody.create(body, MediaType.get("application/json; charset=utf-8")));
        if (retryAfter != null) b.header("Retry-After", retryAfter);
        return b.build();
    }
}
