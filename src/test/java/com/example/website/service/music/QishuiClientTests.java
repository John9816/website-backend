package com.example.website.service.music;

import static org.junit.jupiter.api.Assertions.*;

import com.example.website.common.BusinessException;
import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.dto.music.*;
import com.fasterxml.jackson.databind.*;

import okhttp3.*;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.TimeUnit;

class QishuiClientTests {
    final ObjectMapper mapper = new ObjectMapper();

    static Response response(Interceptor.Chain chain, String body) {
        return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(MediaType.parse("application/json"), body))
                .build();
    }

    static String body(Request request) throws java.io.IOException {
        okio.Buffer buffer = new okio.Buffer();
        request.body().writeTo(buffer);
        return buffer.readUtf8();
    }

    @Test
    void usesClientTokenAndMapsPlatformTrackWithoutLosingLongId() {
        List<Request> requests = new ArrayList<>();
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    Request r = chain.request();
                                    requests.add(r);
                                    if (r.url().encodedPath().contains("token/get")) {
                                        assertEquals("GET", r.method());
                                        assertEquals(
                                                "cold_start",
                                                r.url().queryParameter("trigger_type"));
                                        return response(
                                                chain,
                                                "{\"err_no\":0,\"client_token\":\"catalog-test-token\",\"expires_time\":4102444800}");
                                    }
                                    assertEquals("POST", r.method());
                                    assertEquals("catalog-test-token", r.header("access-token"));
                                    JsonNode b = mapper.readTree(body(r));
                                    assertTrue(b.path("common_params").isObject());
                                    if (r.url().encodedPath().endsWith("search/")) {
                                        assertEquals("track", b.path("search_type").asText());
                                        assertEquals("0", b.path("cursor").asText());
                                        return response(
                                                chain,
                                                "{\"err_no\":0,\"data\":{\"result_groups\":[{\"id\":\"tracks\",\"has_more\":false,\"next_cursor\":\"1\",\"data\":[{\"entity\":{\"track\":"
                                                        + track()
                                                        + "}}]}]}}");
                                    }
                                    return response(
                                            chain,
                                            "{\"err_no\":0,\"data\":{\"media_list\":[{\"track_entity\":"
                                                    + track()
                                                    + "}]}}");
                                })
                        .build();
        QishuiMusicClient client =
                new QishuiMusicClient(new QishuiHttpClient(http, mapper), mapper);
        SongSearchItem item = client.search("test", 1, 10).get(0);
        assertEquals("7646306026912516130", item.getId());
        assertEquals(MusicSource.QISHUI, item.getSource());
        assertEquals("Artist", item.getArtist());
        assertEquals(260480L, item.getDurationMs());
        assertEquals("[00:01.000]测试", client.fetchLyrics(item.getId()).getLineLyrics());
        assertEquals(
                1,
                requests.stream().filter(r -> r.url().encodedPath().contains("token/get")).count());
    }

    static String track() {
        return "{\"base_info\":{\"id\":\"7646306026912516130\",\"name\":\"Test\",\"duration_ms\":260480},\"related_info\":{\"artist_links\":[{\"id\":\"artist1\",\"name\":\"Artist\"}],\"album_link\":{\"id\":\"album1\",\"name\":\"Album\"}},\"display_info\":{\"lyric_info\":{\"lyric_entity\":{\"type\":\"krc\",\"content\":\"[1000,800]<0,300,0>测<300,500,0>试\"}}}}";
    }

    @Test
    void upstreamFailureIsNotAnEmptySearchOrLeakedResponse() {
        QishuiMusicClient client =
                new QishuiMusicClient(
                        new QishuiHttpClient(
                                new OkHttpClient.Builder()
                                        .addInterceptor(
                                                c ->
                                                        response(
                                                                c,
                                                                "{\"err_no\":28001003,\"err_msg\":\"secret"
                                                                    + " upstream detail\"}"))
                                        .build(),
                                mapper),
                        mapper);
        MusicBusinessException e =
                assertThrows(MusicBusinessException.class, () -> client.search("x", 1, 10));
        assertFalse(e.getMessage().contains("secret"));
    }

    @Test
    void expiredBudgetDoesNotSendAnyRequest() {
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                c -> {
                                    fail("must not send");
                                    return null;
                                })
                        .build();
        try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(0, TimeUnit.NANOSECONDS)) {
            assertThrows(
                    MusicBusinessException.class,
                    () ->
                            new QishuiMusicClient(new QishuiHttpClient(http, mapper), mapper)
                                    .search("x", 1, 10));
        }
    }

    @Test
    void newParserRejectsEncryptedUrlsAndUsesNewBearerOnly() {
        QishuiParseClient parser =
                new QishuiParseClient(
                        new QishuiHttpClient(
                                new OkHttpClient.Builder()
                                        .addInterceptor(
                                                c -> {
                                                    assertEquals(
                                                            "Bearer parser-test",
                                                            c.request().header("Authorization"));
                                                    JsonNode b = mapper.readTree(body(c.request()));
                                                    assertEquals("qs", b.path("source").asText());
                                                    assertEquals("id", b.path("trackId").asText());
                                                    return response(
                                                            c,
                                                            "{\"success\":true,\"data\":{\"playUrl\":\"https://media.example/test\",\"encryption\":{\"scheme\":\"qishui-cenc\",\"spadeA\":\"secret\"}}}");
                                                })
                                        .build(),
                                mapper),
                        mapper,
                        "parser-test");
        MusicBusinessException e =
                assertThrows(
                        MusicBusinessException.class, () -> parser.play("id", MusicQuality.K320));
        assertEquals(MusicErrorCode.UNSUPPORTED_OPERATION, e.getErrorCode());
        assertFalse(e.getMessage().contains("secret"));
    }

    @Test
    void repeatedPaginationCursorFailsInsteadOfDuplicatingResults() {
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                c -> {
                                    if (c.request().url().encodedPath().contains("token/get"))
                                        return response(
                                                c,
                                                "{\"err_no\":0,\"client_token\":\"test\",\"expires_time\":4102444800}");
                                    return response(
                                            c,
                                            "{\"err_no\":0,\"data\":{\"result_groups\":[{\"id\":\"tracks\",\"has_more\":true,\"next_cursor\":\"0\",\"data\":[]}]}}");
                                })
                        .build();
        assertThrows(
                MusicBusinessException.class,
                () ->
                        new QishuiMusicClient(new QishuiHttpClient(http, mapper), mapper)
                                .search("x", 1, 10));
    }

    @Test
    void coverTemplateAndKaraokeOffsetsAreMapped() throws Exception {
        assertEquals(
                "https://p3-luna.douyinpic.com/img/test~tplv-example-image.image",
                QishuiMusicClient.imageUrl(
                        mapper.readTree(
                                "{\"urls\":[\"https://p3-luna.douyinpic.com/img/\"],\"uri\":\"test\",\"template_prefix\":\"tplv-example\"}")));
        LyricInfo lyric = QishuiMusicClient.krc("[1000,800]<0,300,0>测<300,500,0>试");
        assertEquals("[1000,800](1000,300,0)测(1300,500,0)试", lyric.getKaraokeLyrics());
    }

    @Test
    void canonicalAliasAndDefaultQuality() {
        assertEquals(MusicSource.QISHUI, MusicSource.of("qs"));
        assertEquals(MusicSource.QISHUI, MusicSource.of("QISHUI"));
        assertEquals(
                "320k", PlaybackQualityRequest.parse(MusicSource.QISHUI, null).getCacheValue());
        assertThrows(MusicBusinessException.class, () -> MusicSource.QISHUI.getTfPayPlatform());
    }

    @Test
    void invalidPlaylistIdentifiersFailBeforeContactingUpstream() {
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    fail("Invalid playlist ID must not contact upstream");
                                    return null;
                                })
                        .build();
        QishuiMusicClient client =
                new QishuiMusicClient(new QishuiHttpClient(http, mapper), mapper);
        for (String id :
                Arrays.asList(
                        "playlist",
                        "playlist:owner:99999999999999999999",
                        "playlist:owner:2147483648",
                        ":owner:2",
                        "playlist::2")) {
            assertEquals(
                    400,
                    assertThrows(
                                    BusinessException.class,
                                    () -> client.fetchPlaylistDetail(id, 1, 10))
                            .getCode());
        }
    }

    @Test
    void malformedCatalogResponsesAreFailuresInsteadOfEmptyResults() {
        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    if (chain.request().url().encodedPath().contains("token/get")) {
                                        return response(
                                                chain,
                                                "{\"err_no\":0,\"client_token\":\"test\",\"expires_time\":4102444800}");
                                    }
                                    return response(chain, "{\"err_no\":0,\"data\":{}}");
                                })
                        .build();
        QishuiMusicClient client =
                new QishuiMusicClient(new QishuiHttpClient(http, mapper), mapper);
        assertEquals(
                MusicErrorCode.UPSTREAM_SEARCH_FAILED,
                assertThrows(
                                MusicBusinessException.class,
                                () -> client.fetchPlaylistDetail("playlist:owner:2", 1, 10))
                        .getErrorCode());
        assertEquals(
                MusicErrorCode.UPSTREAM_SEARCH_FAILED,
                assertThrows(
                                MusicBusinessException.class,
                                () -> client.fetchArtistWorks("artist", "song", 1, 10))
                        .getErrorCode());
        assertEquals(
                MusicErrorCode.UPSTREAM_SEARCH_FAILED,
                assertThrows(MusicBusinessException.class, () -> client.fetchLyrics("track"))
                        .getErrorCode());
    }

    @Test
    void outOfRangeKaraokeTimestampsReturnSanitizedUpstreamErrors() {
        for (String content :
                Arrays.asList(
                        "[999999999999999999999999,800]<0,300,0>text",
                        "[9223372036854775807,800]<1,300,0>text")) {
            MusicBusinessException error =
                    assertThrows(
                            MusicBusinessException.class, () -> QishuiMusicClient.krc(content));
            assertEquals(MusicErrorCode.UPSTREAM_SEARCH_FAILED, error.getErrorCode());
            assertFalse(error.getMessage().contains(content));
        }
    }
}
