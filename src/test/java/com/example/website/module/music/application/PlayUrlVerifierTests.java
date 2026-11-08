package com.example.website.module.music.application;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayUrlVerifierTests {

    @Test
    void reusesSuccessfulProbeVerdictForTheSameUrl() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> range = new AtomicReference<>();
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    calls.incrementAndGet();
                    range.set(chain.request().header("Range"));
                    return response(chain.request(), 206);
                })
                .build();
        PlayUrlVerifier verifier = new PlayUrlVerifier(client);
        String url = "https://wx.music.tc.qq.com/song.m4a?vkey=fresh";

        assertTrue(verifier.isPlayable(url));
        assertTrue(verifier.isPlayable(url));

        assertEquals(1, calls.get());
        assertEquals("bytes=0-0", range.get());
    }

    @Test
    void cachesRejectedVerdictWithoutTreatingUrlAsPlayable() {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    calls.incrementAndGet();
                    return response(chain.request(), 403);
                })
                .build();
        PlayUrlVerifier verifier = new PlayUrlVerifier(client);
        String url = "https://wx.music.tc.qq.com/song.m4a?vkey=expired";

        assertFalse(verifier.isPlayable(url));
        assertFalse(verifier.isPlayable(url));

        assertEquals(1, calls.get());
    }

    private Response response(okhttp3.Request request, int code) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code == 206 ? "Partial Content" : "Forbidden")
                .body(ResponseBody.create(new byte[]{0}, MediaType.get("application/octet-stream")))
                .build();
    }
}
