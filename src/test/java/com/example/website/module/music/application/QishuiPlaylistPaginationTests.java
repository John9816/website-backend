package com.example.website.module.music.application;

import com.example.website.module.music.api.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QishuiPlaylistPaginationTests {
    @Test
    void catalogPageTwoUsesTheRequestedUpstreamOffset() {
        ObjectMapper mapper = new ObjectMapper();
        QishuiMusicClient client = new QishuiMusicClient(new QishuiHttpClient(
                new OkHttpClient.Builder().addInterceptor(chain -> {
                    if (chain.request().url().encodedPath().contains("token/get"))
                        return QishuiClientTests.response(chain,
                                "{\"err_no\":0,\"client_token\":\"test-token\",\"expires_time\":4102444800}");
                    assertEquals("20", mapper.readTree(QishuiClientTests.body(chain.request())).path("cursor").asText());
                    return QishuiClientTests.response(chain,
                            "{\"err_no\":0,\"data\":{\"result_groups\":[{\"id\":\"playlists\",\"has_more\":false,\"data\":[]}]}}");
                }).build(), mapper), mapper);
        MusicCatalogService service = new MusicCatalogService(new MusicCache(), null, null, null, client);
        PlaylistListView page = service.playlists("qs", "摇滚", "hot", 2, 20);
        assertEquals(2, page.getPage());
        assertNull(page.getTotal());
    }
}
