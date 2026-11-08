package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.MusicQuality;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.PlayInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TuneFreePayClientTests {

    @Test
    void emptyUrlDoesNotRefreshTokenAndIsNegativeCached() {
        TuneFreeAuthService auth = mock(TuneFreeAuthService.class);
        when(auth.getToken()).thenReturn("token");
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    calls.incrementAndGet();
                    return response(chain, 200, "{\"data\":{\"data\":[{\"url\":\"\"}]}}");
                })
                .build();
        TuneFreePayClient client = new TuneFreePayClient(auth, http, new ObjectMapper());

        MusicBusinessException first = assertThrows(MusicBusinessException.class,
                () -> client.play(MusicSource.KUWO, "123", MusicQuality.FLAC));
        MusicBusinessException second = assertThrows(MusicBusinessException.class,
                () -> client.play(MusicSource.KUWO, "123", MusicQuality.FLAC));

        assertEquals(MusicErrorCode.NO_PLAYABLE_URL, first.getErrorCode());
        assertEquals(MusicErrorCode.NO_PLAYABLE_URL, second.getErrorCode());
        assertEquals(1, calls.get());
        verify(auth, never()).refresh(true);
    }

    @Test
    void authenticationFailureRefreshesOnlyOnce() {
        TuneFreeAuthService auth = mock(TuneFreeAuthService.class);
        when(auth.getToken()).thenReturn("old-token");
        when(auth.refresh(true)).thenReturn("new-token");
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    if (calls.getAndIncrement() == 0) {
                        return response(chain, 401, "{\"message\":\"token expired\"}");
                    }
                    return response(chain, 200,
                            "{\"data\":{\"data\":[{\"url\":\"https://example.com/song.flac\"}]}}");
                })
                .build();
        TuneFreePayClient client = new TuneFreePayClient(auth, http, new ObjectMapper());

        PlayInfo result = client.play(MusicSource.KUWO, "123", MusicQuality.FLAC);

        assertEquals("https://example.com/song.flac", result.getPlayUrl());
        assertEquals(2, calls.get());
        verify(auth).refresh(true);
    }

    private Response response(Interceptor.Chain chain, int status, String body) throws IOException {
        return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(status >= 200 && status < 300 ? "OK" : "Error")
                .body(ResponseBody.create(body, MediaType.get("application/json; charset=utf-8")))
                .build();
    }
}
