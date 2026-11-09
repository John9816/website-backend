package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import com.example.website.module.music.api.dto.AlbumDetailView;
import com.example.website.module.music.api.dto.ArtistWorksView;
import com.example.website.module.music.api.dto.MusicSearchType;
import com.example.website.module.music.api.dto.SearchCollectionItem;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.OkHttpClient;
import okhttp3.Request;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Coverage for the Qishui catalog endpoints verified against the live upstream on 2026-11-09. */
class QishuiCatalogExtensionTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN_RESPONSE = "{\"err_no\":0,\"client_token\":\"catalog-ext-token\",\"expires_time\":4102444800}";
    private static final String ARTIST_SEARCH = "{\"err_no\":0,\"data\":{\"result_groups\":[{\"id\":\"artists\",\"has_more\":false,\"next_cursor\":\"1\",\"data\":[{\"entity\":{\"artist\":{\"base_info\":{\"id\":\"6681166129722824706\",\"name\":\"周杰伦\",\"count_tracks\":\"22\",\"url_avatar\":{\"urls\":[\"https://img.example/\"],\"uri\":\"a.png\"}}}}}]}]}}";
    private static final String TRACK_SEARCH = "{\"err_no\":0,\"data\":{\"result_groups\":[{\"id\":\"tracks\",\"has_more\":false,\"next_cursor\":\"3\",\"data\":[{\"entity\":{\"track\":{\"base_info\":{\"id\":\"1\",\"name\":\"歌曲1\",\"duration_ms\":\"226533\"},\"related_info\":{\"album_link\":{\"id\":\"A1\",\"name\":\"专辑一\"},\"artist_links\":[{\"id\":\"AR1\",\"name\":\"蔡依林\"}]},\"display_info\":{\"cover_url\":{\"urls\":[\"https://img.example/\"],\"uri\":\"c.jpg\"}}}}},{\"entity\":{\"track\":{\"base_info\":{\"id\":\"2\",\"name\":\"歌曲2\",\"duration_ms\":\"226533\"},\"related_info\":{\"album_link\":{\"id\":\"A1\",\"name\":\"专辑一\"},\"artist_links\":[{\"id\":\"AR1\",\"name\":\"蔡依林\"}]},\"display_info\":{\"cover_url\":{\"urls\":[\"https://img.example/\"],\"uri\":\"c.jpg\"}}}}},{\"entity\":{\"track\":{\"base_info\":{\"id\":\"3\",\"name\":\"歌曲3\",\"duration_ms\":\"226533\"},\"related_info\":{\"album_link\":{\"id\":\"A2\",\"name\":\"专辑二\"},\"artist_links\":[{\"id\":\"AR1\",\"name\":\"蔡依林\"}]},\"display_info\":{\"cover_url\":{\"urls\":[\"https://img.example/\"],\"uri\":\"c.jpg\"}}}}}]}]}}";
    private static final String ALBUM_DETAIL = "{\"err_no\":0,\"data\":{\"album_info\":{\"base_info\":{\"id\":\"A1\",\"name\":\"看我72变\",\"count_tracks\":\"11\",\"release_date\":\"1046966400\",\"intro\":\"专辑简介\"},\"display\":{\"url_cover\":{\"urls\":[\"https://img.example/\"],\"uri\":\"cover.jpg\"}},\"related_info\":{\"artists\":[{\"id\":\"AR1\",\"name\":\"蔡依林\"}]}},\"tracks\":[{\"base_info\":{\"id\":\"S1\",\"name\":\"歌曲S1\",\"duration_ms\":\"226533\"},\"related_info\":{\"album_link\":{\"id\":\"A1\",\"name\":\"看我72变\"},\"artist_links\":[{\"id\":\"AR1\",\"name\":\"蔡依林\"}]},\"display_info\":{\"cover_url\":{\"urls\":[\"https://img.example/\"],\"uri\":\"c.jpg\"}}}]}}";
    private static final String ARTIST_ALBUMS = "{\"err_no\":0,\"data\":{\"albums\":[{\"base_info\":{\"id\":\"AL1\",\"name\":\"Emoji\",\"count_tracks\":\"1\",\"release_date\":\"1774483200\",\"intro\":\"\"},\"display\":{\"url_cover\":{\"urls\":[\"https://img.example/\"],\"uri\":\"al.jpg\"}},\"related_info\":{\"artists\":[{\"id\":\"AR1\",\"name\":\"歌手\"}]}}],\"has_more\":false,\"next_cursor\":\"1\"}}";

    interface Responder { String respond(Request r) throws IOException; }

    private QishuiMusicClient client(Responder responder) {
        OkHttpClient http = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request r = chain.request();
                    if (r.url().encodedPath().contains("token/get"))
                        return QishuiClientTests.response(chain, TOKEN_RESPONSE);
                    return QishuiClientTests.response(chain, responder.respond(r));
                }).build();
        return new QishuiMusicClient(new QishuiHttpClient(http, MAPPER), MAPPER);
    }

    @Test
    void artistSearchUsesArtistsGroupAndMapsFields() {
        QishuiMusicClient c = client(r -> {
            assertEquals("artist", MAPPER.readTree(QishuiClientTests.body(r)).path("search_type").asText());
            return ARTIST_SEARCH;
        });
        List<SearchCollectionItem> items = c.searchCollections("周杰伦", MusicSearchType.ARTIST, 1, 10).getList();
        assertEquals(1, items.size());
        SearchCollectionItem a = items.get(0);
        assertEquals("6681166129722824706", a.getId());
        assertEquals("周杰伦", a.getName());
        assertEquals(MusicSearchType.ARTIST, a.getType());
        assertEquals(22, a.getTrackCount());
        assertNotNull(a.getCoverUrl());
    }

    @Test
    void albumSearchAggregatesDistinctAlbumsFromRealTrackHits() {
        QishuiMusicClient c = client(r -> TRACK_SEARCH);
        List<SearchCollectionItem> items = c.searchCollections("周杰伦", MusicSearchType.ALBUM, 1, 10).getList();
        assertEquals(2, items.size());
        assertEquals("A1", items.get(0).getId());
        assertEquals("专辑一", items.get(0).getName());
        assertEquals("蔡依林", items.get(0).getArtist());
        assertEquals(MusicSearchType.ALBUM, items.get(0).getType());
    }

    @Test
    void albumDetailMapsAlbumInfoTracksAndReleaseDate() {
        QishuiMusicClient c = client(r -> {
            assertTrue(r.url().encodedPath().endsWith("album/detail/"));
            assertEquals("A1", MAPPER.readTree(QishuiClientTests.body(r)).path("album_id").asText());
            return ALBUM_DETAIL;
        });
        AlbumDetailView v = c.fetchAlbumDetail("A1", 1, 20);
        assertEquals("看我72变", v.getName());
        assertEquals("蔡依林", v.getArtist());
        assertEquals("2003-03-07", v.getPublishTime());
        assertEquals(11, v.getTotal());
        assertEquals(1, v.getList().size());
        assertEquals("S1", v.getList().get(0).getId());
    }

    @Test
    void artistWorksAlbumReturnsMappedAlbums() {
        QishuiMusicClient c = client(r -> {
            assertTrue(r.url().encodedPath().endsWith("artist/albums/"));
            return ARTIST_ALBUMS;
        });
        ArtistWorksView v = c.fetchArtistWorks("AR1", "album", 1, 20);
        assertEquals("album", v.getType());
        assertTrue(v.getSongs().isEmpty());
        assertEquals(1, v.getAlbums().size());
        assertEquals("AL1", v.getAlbums().get(0).getId());
        assertEquals("Emoji", v.getAlbums().get(0).getName());
        assertEquals("2026-03-26", v.getAlbums().get(0).getPublishTime());
    }

    @Test
    void discoveryViewsAreExplicitlyLabelledAsSearchBased() {
        QishuiMusicClient c = client(r -> TRACK_SEARCH);
        assertEquals(3, c.discoveryToplists().size());
        assertTrue(c.discoveryToplists().get(0).getDescription().contains("非官方榜单"));
        assertTrue(c.discoveryToplistDetail("热门", 1, 10).getDescription().contains("非官方榜单"));
        assertFalse(c.discoveryToplistDetail("新歌", 1, 10).getList().isEmpty());
        assertThrows(BusinessException.class, () -> c.discoveryToplistDetail("任意关键词", 1, 10));
    }

    @Test
    void discoveryPlaylistsUseRealPlaylistSearchAndDeduplicate() {
        QishuiMusicClient c = client(r -> "{\"err_no\":0,\"data\":{\"result_groups\":[{\"id\":\"playlists\",\"has_more\":false,\"next_cursor\":\"1\",\"data\":[{\"entity\":{\"playlist\":{\"base_info\":{\"id\":\"PL1\",\"owner_id\":\"O1\",\"type\":2,\"title\":\"热门歌单\"},\"display_info\":{\"url_cover\":{\"urls\":[\"https://img.example/\"],\"uri\":\"p.jpg\"}}}}}]}]}}");
        assertFalse(c.discoveryPlaylists(6).isEmpty());
    }
}
