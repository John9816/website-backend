package com.example.website;

import com.example.website.dto.music.MusicQuality;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.PlayInfo;
import com.example.website.service.music.TuneFreePayClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MusicLibraryIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TuneFreePayClient tuneFreePayClient;

    @Test
    void musicLibraryEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/user/music/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/user/music/favorites"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/user/music/favorites/status")
                        .param("source", "qq")
                        .param("songId", "song-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/user/music/shares/status")
                        .param("source", "qq")
                        .param("songId", "song-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void authenticatedPlayPersistsHistoryAndKeepsOnlyMostRecent100Songs() throws Exception {
        mockPlayResponses();
        String token = registerAndExtractToken("music_history_user", "secret123");

        for (int i = 1; i <= 101; i++) {
            mockMvc.perform(get("/api/v1/music/play")
                            .header("Authorization", "Bearer " + token)
                            .param("source", "qq")
                            .param("id", "song-" + i)
                            .param("quality", "128k"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.id").value("song-" + i));
        }

        mockMvc.perform(get("/api/v1/music/play")
                        .header("Authorization", "Bearer " + token)
                        .param("source", "qq")
                        .param("id", "song-42")
                        .param("quality", "128k"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        JsonNode history = getJson("/api/user/music/history?page=0&size=120", token).path("data");
        JsonNode items = history.path("items");
        assertEquals(100, history.path("total").asInt());
        assertEquals(100, items.size());
        assertEquals("song-42", items.get(0).path("songId").asText());
        assertFalse(containsSong(items, "song-1"));
        assertEquals(1, countSong(items, "song-42"));
        assertTrue(items.get(0).path("playedAt").asText().length() > 0);
    }

    @Test
    void favoritesCrudIsScopedPerUserAndUpsertsSameSong() throws Exception {
        String aliceToken = registerAndExtractToken("music_fav_alice", "secret123");
        String bobToken = registerAndExtractToken("music_fav_bob", "secret123");

        String body = "{\"source\":\"qq\",\"songId\":\"fav-song-1\",\"name\":\"Favorite Song 1\",\"artist\":\"Artist A\",\"album\":\"Album A\",\"coverUrl\":\"https://example.com/fav1.jpg\",\"durationSec\":213}";

        JsonNode firstSave = readJson(mockMvc.perform(post("/api/user/music/favorites")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()).path("data");

        JsonNode secondSave = readJson(mockMvc.perform(post("/api/user/music/favorites")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()).path("data");

        assertEquals(firstSave.path("id").asLong(), secondSave.path("id").asLong());

        JsonNode aliceFavorites = getJson("/api/user/music/favorites?page=0&size=20", aliceToken).path("data");
        assertEquals(1, aliceFavorites.path("total").asInt());
        assertEquals("fav-song-1", aliceFavorites.path("items").get(0).path("songId").asText());

        JsonNode aliceStatus = getJson("/api/user/music/favorites/status?source=qq&songId=fav-song-1", aliceToken)
                .path("data");
        assertTrue(aliceStatus.path("liked").asBoolean());
        assertEquals(firstSave.path("id").asLong(), aliceStatus.path("favoriteId").asLong());

        JsonNode bobStatus = getJson("/api/user/music/favorites/status?source=qq&songId=fav-song-1", bobToken)
                .path("data");
        assertFalse(bobStatus.path("liked").asBoolean());
        assertTrue(bobStatus.path("favoriteId").isNull());

        JsonNode bobFavorites = getJson("/api/user/music/favorites?page=0&size=20", bobToken).path("data");
        assertEquals(0, bobFavorites.path("total").asInt());

        mockMvc.perform(delete("/api/user/music/favorites")
                        .header("Authorization", "Bearer " + bobToken)
                        .param("source", "qq")
                        .param("songId", "fav-song-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(delete("/api/user/music/favorites")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("source", "qq")
                        .param("songId", "fav-song-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        JsonNode afterDelete = getJson("/api/user/music/favorites/status?source=qq&songId=fav-song-1", aliceToken)
                .path("data");
        assertFalse(afterDelete.path("liked").asBoolean());
    }

    @Test
    void musicSharesCanBeCreatedRotatedViewedAndDeleted() throws Exception {
        mockPlayResponses();
        String token = registerAndExtractToken("music_share_user", "secret123");

        String body = "{\"source\":\"qq\",\"songId\":\"share-song-1\",\"name\":\"Share Song 1\",\"artist\":\"Artist S\",\"album\":\"Album S\",\"coverUrl\":\"https://example.com/share1.jpg\",\"durationSec\":245,\"requestedQuality\":\"320k\"}";

        JsonNode firstSave = readJson(mockMvc.perform(post("/api/user/music/shares")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()).path("data");

        JsonNode secondSave = readJson(mockMvc.perform(post("/api/user/music/shares")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()).path("data");

        assertEquals(firstSave.path("id").asLong(), secondSave.path("id").asLong());
        assertEquals(firstSave.path("token").asText(), secondSave.path("token").asText());

        String rotatedBody = "{\"source\":\"qq\",\"songId\":\"share-song-1\",\"name\":\"Share Song 1\",\"artist\":\"Artist S\",\"album\":\"Album S\",\"coverUrl\":\"https://example.com/share1.jpg\",\"durationSec\":245,\"requestedQuality\":\"320k\",\"rotateToken\":true}";

        JsonNode rotatedSave = readJson(mockMvc.perform(post("/api/user/music/shares")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotatedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()).path("data");

        assertEquals(firstSave.path("id").asLong(), rotatedSave.path("id").asLong());
        assertFalse(firstSave.path("token").asText().equals(rotatedSave.path("token").asText()));

        JsonNode statusView = getJson("/api/user/music/shares/status?source=qq&songId=share-song-1", token)
                .path("data");
        assertEquals(rotatedSave.path("token").asText(), statusView.path("token").asText());
        assertEquals("320k", statusView.path("requestedQuality").asText());

        mockMvc.perform(get("/api/public/music/share/{token}", firstSave.path("token").asText()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(404));

        JsonNode publicView = readJson(mockMvc.perform(get("/api/public/music/share/{token}", rotatedSave.path("token").asText()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(0))
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=300"))
                        .andReturn())
                .path("data");

        assertEquals("share-song-1", publicView.path("songId").asText());
        assertEquals("qq", publicView.path("source").asText());
        assertTrue(publicView.path("playable").asBoolean());
        assertEquals("share-song-1", publicView.path("playInfo").path("id").asText());
        assertEquals(1, publicView.path("viewCount").asInt());

        JsonNode afterViewStatus = getJson("/api/user/music/shares/status?source=qq&songId=share-song-1", token)
                .path("data");
        assertEquals(1, afterViewStatus.path("viewCount").asInt());

        mockMvc.perform(delete("/api/user/music/shares")
                        .header("Authorization", "Bearer " + token)
                        .param("source", "qq")
                        .param("songId", "share-song-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        JsonNode deletedStatus = getJson("/api/user/music/shares/status?source=qq&songId=share-song-1", token)
                .path("data");
        assertTrue(deletedStatus.isNull());

        mockMvc.perform(get("/api/public/music/share/{token}", rotatedSave.path("token").asText()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(404));
    }

    private void mockPlayResponses() {
        when(tuneFreePayClient.play(any(MusicSource.class), anyString(), any(MusicQuality.class)))
                .thenAnswer(invocation -> {
                    MusicSource source = invocation.getArgument(0);
                    String songId = invocation.getArgument(1);
                    MusicQuality quality = invocation.getArgument(2);
                    PlayInfo info = new PlayInfo();
                    info.setId(songId);
                    info.setSource(source);
                    info.setActualSource(source);
                    info.setName("Track " + songId);
                    info.setArtist("Artist " + songId);
                    info.setAlbum("Album " + songId);
                    info.setCoverUrl("https://example.com/" + songId + ".jpg");
                    info.setDurationSec(180);
                    info.setPlayUrl("https://example.com/play/" + songId);
                    info.setActualQuality(quality.getValue());
                    return info;
                });
    }

    private String registerAndExtractToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).path("data").path("token").asText();
    }

    private String registerBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"email\":\"" + qqEmail(username)
                + "\",\"password\":\"" + password + "\"}";
    }

    private String qqEmail(String seed) {
        CRC32 crc32 = new CRC32();
        crc32.update(seed.getBytes(StandardCharsets.UTF_8));
        long number = 10000L + (crc32.getValue() % 9999999999L);
        return number + "@qq.com";
    }

    private JsonNode getJson(String path, String token) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result);
    }

    private boolean containsSong(JsonNode items, String songId) {
        return countSong(items, songId) > 0;
    }

    private int countSong(JsonNode items, String songId) {
        int count = 0;
        for (JsonNode item : items) {
            if (songId.equals(item.path("songId").asText())) {
                count++;
            }
        }
        return count;
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
