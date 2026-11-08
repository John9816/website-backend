package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.AppProperties;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.NeteasePlaybackQuality;
import com.example.website.module.music.api.dto.PlayInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChkszNeteaseMusicClientTests {

    private static final String TEST_KEY = "test-api-key-never-log";

    @ParameterizedTest
    @EnumSource(NeteasePlaybackQuality.class)
    void sendsEverySupportedLevel(NeteasePlaybackQuality quality) {
        AtomicReference<okhttp3.HttpUrl> requestedUrl = new AtomicReference<>();
        ChkszNeteaseMusicClient client = client(chain -> {
            requestedUrl.set(chain.request().url());
            return response(chain, 200, successBody(quality.getValue()), null);
        });

        client.resolve("2034742057", quality);

        assertEquals("/api/163_music", requestedUrl.get().encodedPath());
        assertEquals("2034742057", requestedUrl.get().queryParameter("id"));
        assertEquals(quality.getValue(), requestedUrl.get().queryParameter("level"));
        assertEquals("json", requestedUrl.get().queryParameter("type"));
        assertEquals(TEST_KEY, requestedUrl.get().queryParameter("apikey"));
    }

    @Test
    void mapsSuccessfulResponse() {
        ChkszNeteaseMusicClient client = client(chain -> response(chain, 200,
                "{\"code\":200,\"msg\":\"success\",\"data\":{"
                        + "\"id\":1315196858,"
                        + "\"url\":\"https://cdn.example.com/song.flac\","
                        + "\"level\":\"lossless\","
                        + "\"size\":34567890,"
                        + "\"name\":\"海底\","
                        + "\"artist\":\"一支榴莲\","
                        + "\"album\":\"独\","
                        + "\"picUrl\":\"https://img.example.com/cover.jpg\"}}", null));

        PlayInfo info = client.resolve("1315196858", NeteasePlaybackQuality.LOSSLESS);

        assertEquals("1315196858", info.getId());
        assertEquals(MusicSource.NETEASE, info.getSource());
        assertEquals(MusicSource.NETEASE, info.getActualSource());
        assertEquals("https://cdn.example.com/song.flac", info.getPlayUrl());
        assertEquals("lossless", info.getActualQuality());
        assertEquals(Long.valueOf(34567890L), info.getFileSize());
        assertEquals("海底", info.getName());
        assertEquals("一支榴莲", info.getArtist());
        assertEquals("独", info.getAlbum());
        assertEquals("https://img.example.com/cover.jpg", info.getCoverUrl());
        assertFalse(Boolean.TRUE.equals(info.getFromCache()));
    }

    @Test
    void disabledClientMakesNoRequest() {
        AtomicInteger calls = new AtomicInteger();
        AppProperties properties = properties(false, TEST_KEY);
        ChkszNeteaseMusicClient client = client(properties, chain -> {
            calls.incrementAndGet();
            return response(chain, 200, successBody("lossless"), null);
        }, new AtomicLong(1_000L));

        MusicBusinessException error = assertThrows(MusicBusinessException.class,
                () -> client.resolve("1", NeteasePlaybackQuality.LOSSLESS));

        assertEquals(MusicErrorCode.NO_PLAYABLE_URL, error.getErrorCode());
        assertEquals(0, calls.get());
    }

    @Test
    void missingKeyMakesNoRequest() {
        AtomicInteger calls = new AtomicInteger();
        AppProperties properties = properties(true, " ");
        ChkszNeteaseMusicClient client = client(properties, chain -> {
            calls.incrementAndGet();
            return response(chain, 200, successBody("lossless"), null);
        }, new AtomicLong(1_000L));

        MusicBusinessException error = assertThrows(MusicBusinessException.class,
                () -> client.resolve("1", NeteasePlaybackQuality.LOSSLESS));

        assertEquals(MusicErrorCode.NO_PLAYABLE_URL, error.getErrorCode());
        assertEquals(0, calls.get());
    }

    @Test
    void badRequestIsNegativeCachedWithoutLeakingKey() {
        AtomicInteger calls = new AtomicInteger();
        ChkszNeteaseMusicClient client = client(chain -> {
            calls.incrementAndGet();
            return response(chain, 400, "{\"code\":400,\"msg\":\"bad id\"}", null);
        });

        MusicBusinessException first = assertThrows(MusicBusinessException.class,
                () -> client.resolve("bad", NeteasePlaybackQuality.HIRES));
        MusicBusinessException second = assertThrows(MusicBusinessException.class,
                () -> client.resolve("bad", NeteasePlaybackQuality.HIRES));

        assertEquals(MusicErrorCode.NO_PLAYABLE_URL, first.getErrorCode());
        assertEquals(MusicErrorCode.NO_PLAYABLE_URL, second.getErrorCode());
        assertEquals(1, calls.get());
        assertSecretAbsent(first);
        assertSecretAbsent(second);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void authorizationFailureStartsGlobalCooldown(int status) {
        AtomicInteger calls = new AtomicInteger();
        ChkszNeteaseMusicClient client = client(chain -> {
            calls.incrementAndGet();
            return response(chain, status, "{\"code\":" + status + ",\"msg\":\"denied\"}", null);
        });

        MusicBusinessException first = assertThrows(MusicBusinessException.class,
                () -> client.resolve("1", NeteasePlaybackQuality.STANDARD));
        MusicBusinessException second = assertThrows(MusicBusinessException.class,
                () -> client.resolve("2", NeteasePlaybackQuality.EXHIGH));

        assertEquals(1, calls.get());
        assertSecretAbsent(first);
        assertSecretAbsent(second);
    }

    @Test
    void exhaustedQuotaStartsGlobalCooldown() {
        AtomicInteger calls = new AtomicInteger();
        ChkszNeteaseMusicClient client = client(chain -> {
            calls.incrementAndGet();
            return response(chain, 402, "{\"code\":402,\"msg\":\"quota exhausted\"}", null);
        });

        MusicBusinessException first = assertThrows(MusicBusinessException.class,
                () -> client.resolve("1", NeteasePlaybackQuality.LOSSLESS));
        MusicBusinessException second = assertThrows(MusicBusinessException.class,
                () -> client.resolve("2", NeteasePlaybackQuality.JYMASTER));

        assertEquals(1, calls.get());
        assertSecretAbsent(first);
        assertSecretAbsent(second);
    }

    @Test
    void rateLimitHonorsRetryAfterWithoutRetryingCurrentRequest() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(1_000L);
        ChkszNeteaseMusicClient client = client(properties(true, TEST_KEY), chain -> {
            calls.incrementAndGet();
            return response(chain, 429, "{\"code\":429,\"msg\":\"slow down\"}", "42");
        }, clock);

        assertThrows(MusicBusinessException.class,
                () -> client.resolve("1", NeteasePlaybackQuality.SKY));
        clock.addAndGet(41_000L);
        assertThrows(MusicBusinessException.class,
                () -> client.resolve("2", NeteasePlaybackQuality.SKY));
        assertEquals(1, calls.get());

        clock.addAndGet(1_000L);
        assertThrows(MusicBusinessException.class,
                () -> client.resolve("3", NeteasePlaybackQuality.SKY));
        assertEquals(2, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json",
            "{\"code\":503,\"msg\":\"upstream failed\",\"data\":null}",
            "{\"code\":200,\"msg\":\"success\",\"data\":{\"url\":\"\"}}"
    })
    void malformedOrUnusableResponsesFailWithoutLeakingKey(String body) {
        AtomicInteger calls = new AtomicInteger();
        ChkszNeteaseMusicClient client = client(chain -> {
            calls.incrementAndGet();
            return response(chain, 200, body, null);
        });

        MusicBusinessException error = assertThrows(MusicBusinessException.class,
                () -> client.resolve("1", NeteasePlaybackQuality.JYEFFECT));

        assertEquals(1, calls.get());
        assertSecretAbsent(error);
    }

    @Test
    void serviceUnavailableDoesNotRetry() {
        AtomicInteger calls = new AtomicInteger();
        ChkszNeteaseMusicClient client = client(chain -> {
            calls.incrementAndGet();
            return response(chain, 503, "{\"code\":503,\"msg\":\"unavailable\"}", null);
        });

        MusicBusinessException error = assertThrows(MusicBusinessException.class,
                () -> client.resolve("1", NeteasePlaybackQuality.JYMASTER));

        assertEquals(MusicErrorCode.UPSTREAM_PLAY_FAILED, error.getErrorCode());
        assertEquals(1, calls.get());
        assertSecretAbsent(error);
    }

    private ChkszNeteaseMusicClient client(Interceptor interceptor) {
        return client(properties(true, TEST_KEY), interceptor, new AtomicLong(1_000L));
    }

    private ChkszNeteaseMusicClient client(AppProperties properties,
                                            Interceptor interceptor,
                                            AtomicLong clock) {
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(interceptor).build();
        return new ChkszNeteaseMusicClient(properties, http, new ObjectMapper(), clock::get);
    }

    private AppProperties properties(boolean enabled, String key) {
        AppProperties properties = new AppProperties();
        properties.getMusic().getChksz().setEnabled(enabled);
        properties.getMusic().getChksz().setBaseUrl("https://api.chksz.test");
        properties.getMusic().getChksz().setApiKey(key);
        return properties;
    }

    private static String successBody(String level) {
        return "{\"code\":200,\"msg\":\"success\",\"data\":{"
                + "\"id\":2034742057,"
                + "\"url\":\"https://cdn.example.com/song.flac\","
                + "\"level\":\"" + level + "\"}}";
    }

    private static Response response(Interceptor.Chain chain,
                                     int status,
                                     String body,
                                     String retryAfter) throws IOException {
        Response.Builder builder = new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(status >= 200 && status < 300 ? "OK" : "Error")
                .body(ResponseBody.create(body, MediaType.get("application/json; charset=utf-8")));
        if (retryAfter != null) builder.header("Retry-After", retryAfter);
        return builder.build();
    }

    private static void assertSecretAbsent(Throwable error) {
        String message = error.getMessage();
        assertFalse(message != null && message.contains(TEST_KEY));
    }
}
