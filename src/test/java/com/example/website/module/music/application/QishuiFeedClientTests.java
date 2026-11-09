package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.module.music.api.dto.*;
import com.fasterxml.jackson.databind.*;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QishuiFeedClientTests {
    private final ObjectMapper mapper = new ObjectMapper();
    // Actual /feed/song-tab/ cards use type=track, despite the nested entity.media key.
    static final String SONG = "{\"type\":\"track\",\"entity\":{\"media\":{\"media_type\":\"track\",\"track_entity\":{\"base_info\":{\"id\":\"9000000000000000001\",\"name\":\"测试歌曲\"}}}}}";
    static final String PAGE = "{\"err_no\":0,\"data\":{\"has_more\":true,\"items\":[" + SONG + "," + SONG + ",{\"type\":\"ad\"}]}}";
    static final String MODES = "{\"err_no\":0,\"data\":{\"feed_mode_block\":[{\"title\":\"场景\",\"feed_mode\":[{\"entity\":{\"feed_mode\":{\"type\":\"feed_scene_mode\",\"text\":\"运动\",\"cutover_toast\":\"已切换\",\"scene_mode\":{\"scene_mode_id\":2},\"url_info\":{\"urls\":[\"https://img.example/icon.png\"]}}}}]}]}}";

    interface Verify { void check(Request r, JsonNode body) throws Exception; }
    private QishuiFeedClient client(String response, Verify verify) {
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            if (chain.request().url().encodedPath().contains("token/get"))
                return QishuiClientTests.response(chain, "{\"err_no\":0,\"client_token\":\"feed-token\",\"expires_time\":4102444800}");
            try { verify.check(chain.request(), mapper.readTree(QishuiClientTests.body(chain.request()))); }
            catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new AssertionError(e); }
            return QishuiClientTests.response(chain, response);
        }).build();
        return new QishuiFeedClient(new QishuiMusicClient(new QishuiHttpClient(http, mapper), mapper), mapper);
    }

    @Test void discoveryUsesRealFeedAndDropsDuplicateOrNonMusicCards() {
        QishuiDiscoveryView result = client(PAGE, (r,b) -> {
            assertTrue(r.url().encodedPath().endsWith("/feed/song-tab/"));
            assertEquals(12, b.path("count").asInt());
            assertTrue(b.path("common_params").isEmpty());
            assertTrue(b.path("is_first_request").asBoolean());
            assertFalse(b.path("is_did_first_request").asBoolean());
            assertTrue(b.path("feed_preference").path("scene_mode_id").isNull());
        }).discovery();
        assertEquals(1, result.getList().size());
        assertEquals("9000000000000000001", result.getList().get(0).getId());
    }

    @Test void personalFeedUsesCatalogTokenAndBoundIdentityWithSceneCounters() {
        QishuiCredential c = new QishuiCredential(); c.setUserId("upstream-user"); c.setDeviceId("bound-device");
        QishuiFeedClient.Page result = client(PAGE, (r,b) -> {
            assertEquals("feed-token", r.header("access-token"));
            assertNull(r.header("bd-ticket-guard-client-data"));
            assertEquals("bound-device", r.url().queryParameter("device_id"));
            assertEquals("upstream-user", b.path("common_params").path("user_id").asText());
            assertEquals(20, b.path("count").asInt());
            assertFalse(b.path("is_first_request").asBoolean());
            assertTrue(b.path("with_client_cache").asBoolean());
            assertTrue(b.path("is_did_first_request").asBoolean());
            assertEquals(4, b.path("feed_preference").path("total_request_count").asInt());
            assertEquals(2, b.path("feed_preference").path("current_mode_request_count").asInt());
            assertEquals(2, b.path("feed_preference").path("scene_mode_id").asInt());
            assertEquals(100L, b.path("feed_preference").path("scene_mode_select_time").asLong());
        }).songs(c, "2", 4, 2, 100L);
        assertTrue(result.hasMore);
        assertEquals(1, result.songs.size());
    }

    @Test void modesIncludeDefaultAndParseNestedSceneCards() {
        List<QishuiFeedModeView> modes = client(MODES, (r,b) ->
                assertTrue(r.url().encodedPath().endsWith("/feed/mode/"))).modes(null);
        assertEquals(Arrays.asList("recommended", "2"), Arrays.asList(modes.get(0).getId(), modes.get(1).getId()));
        assertEquals("运动", modes.get(1).getName());
        assertEquals("场景", modes.get(1).getGroup());
        assertEquals("https://img.example/icon.png", modes.get(1).getIconUrl());
    }

    @Test void malformedAndFailedResponsesNeverBecomeAnEmptySuccess() {
        for (String response : Arrays.asList("{\"err_no\":1001}", "{\"err_no\":0,\"data\":{\"items\":[]}}", "{\"err_no\":0,\"data\":{\"has_more\":true,\"items\":{}}}"))
            assertThrows(MusicBusinessException.class, () -> client(response, (r,b) -> {}).discovery());
        assertThrows(MusicBusinessException.class, () -> client("{\"err_no\":0,\"data\":{}}", (r,b) -> {}).modes(null));
    }
}
