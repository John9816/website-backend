package com.example.website.module.music.application;

import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.AlbumDetailView;
import com.example.website.module.music.api.dto.ArtistWorksView;
import com.example.website.module.music.api.dto.PlaylistListView;
import com.example.website.module.music.api.dto.SongSearchItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeteaseMusicClientTests {

    @Test
    void searchFallsBackToLegacyAndBuildsCoverUrlFromPicId() {
        List<String> paths = new ArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(fakeNeteaseSearch(paths))
                .build();
        NeteaseMusicClient netease = new NeteaseMusicClient(client, new ObjectMapper());

        List<SongSearchItem> songs = netease.search("jay", 1, 10);

        assertEquals(1, songs.size());
        SongSearchItem song = songs.get(0);
        assertEquals(MusicSource.NETEASE, song.getSource());
        assertEquals("5257138", song.getId());
        assertEquals("Rooftop", song.getName());
        assertEquals("Jay Chou / Landy Wen", song.getArtist());
        assertEquals("6452", song.getArtistId());
        assertEquals(Arrays.asList("6452", "6453"), song.getArtistIds());
        assertEquals("Duets Collection", song.getAlbum());
        assertEquals("512175", song.getAlbumId());
        assertEquals(
                "https://music.163.com/api/img/blur/109951165671182684?param=130y130",
                song.getCoverUrl());
        assertTrue(paths.stream().anyMatch(path -> path.contains("/api/cloudsearch/pc")));
        assertTrue(paths.stream().anyMatch(path -> path.contains("/api/search/get/web")));
    }

    @Test
    void neteaseImageUrlIgnoresMissingPicId() {
        assertNull(NeteaseMusicClient.neteaseImageUrl(null));
        assertNull(NeteaseMusicClient.neteaseImageUrl(0));
    }

    @Test
    void recommendedPlaylistsUseAnonymousPersonalizedEndpoint() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            assertEquals("/api/personalized/playlist", chain.request().url().encodedPath());
            assertEquals("6", chain.request().url().queryParameter("limit"));
            assertEquals("os=pc", chain.request().header("Cookie"));
            return response(chain, 200, "{\"code\":200,\"hasTaste\":false,\"result\":[{"
                    + "\"id\":17583805570,\"name\":\"Recommended\",\"picUrl\":\"https://img/recommended.jpg\","
                    + "\"copywriter\":\"For you\",\"trackCount\":70,\"playCount\":24766}]}" );
        }).build();
        NeteaseMusicClient netease = new NeteaseMusicClient(client, new ObjectMapper());

        PlaylistListView view = netease.fetchRecommendedPlaylists(6);

        assertEquals(MusicSource.NETEASE, view.getSource());
        assertEquals("recommend", view.getCategory());
        assertEquals(1L, view.getTotal());
        assertEquals("17583805570", view.getList().get(0).getId());
        assertEquals("For you", view.getList().get(0).getDescription());
        assertEquals(70, view.getList().get(0).getTrackCount());
    }

    @Test
    void albumDetailMapsLegacyQualityFieldsAndPaginates() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> response(chain, 200,
                "{\"code\":200,\"album\":{\"id\":34720827,\"name\":\"Album\","
                        + "\"picUrl\":\"https://img/album.jpg\",\"publishTime\":1466697600000,\"size\":2,"
                        + "\"artists\":[{\"id\":6452,\"name\":\"Artist\"}],\"songs\":["
                        + "{\"id\":1,\"name\":\"One\",\"duration\":200000,\"artists\":[{\"id\":6452,\"name\":\"Artist\"}],"
                        + "\"album\":{\"id\":34720827,\"name\":\"Album\",\"picUrl\":\"https://img/album.jpg\"},"
                        + "\"lMusic\":{},\"hMusic\":{},\"sqMusic\":{}},"
                        + "{\"id\":2,\"name\":\"Two\"}]}}"))
                .build();
        NeteaseMusicClient netease = new NeteaseMusicClient(client, new ObjectMapper());

        AlbumDetailView album = netease.fetchAlbumDetail("34720827", 1, 1);

        assertEquals(2, album.getTotal());
        assertEquals(1, album.getList().size());
        assertEquals(Arrays.asList("128k", "320k", "flac"), album.getList().get(0).getAvailableQualities());
        assertEquals("2016-06-24", album.getPublishTime());
    }

    @Test
    void artistWorksMapsOfficialSongResponse() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> response(chain, 200,
                "{\"code\":200,\"total\":566,\"songs\":[{\"id\":298317,\"name\":\"Rooftop\","
                        + "\"artists\":[{\"id\":9612,\"name\":\"Landy\"},{\"id\":6452,\"name\":\"Jay\"}],"
                        + "\"album\":{\"id\":29597,\"name\":\"Album\",\"picUrl\":\"https://img/album.jpg\"}}]}"))
                .build();
        NeteaseMusicClient netease = new NeteaseMusicClient(client, new ObjectMapper());

        ArtistWorksView works = netease.fetchArtistWorks("6452", "song", 1, 20);

        assertEquals(566, works.getTotal());
        assertEquals(Arrays.asList("9612", "6452"), works.getSongs().get(0).getArtistIds());
    }

    private Interceptor fakeNeteaseSearch(List<String> paths) {
        return chain -> {
            String path = chain.request().url().encodedPath();
            paths.add(path);
            if (path.contains("/api/cloudsearch/pc")) {
                return response(chain, 502, "{\"code\":502}");
            }
            return response(chain, 200,
                    "{"
                            + "\"result\":{\"songs\":[{"
                            + "\"id\":5257138,"
                            + "\"name\":\"Rooftop\","
                            + "\"album\":{\"id\":512175,\"name\":\"Duets Collection\",\"picId\":109951165671182684},"
                            + "\"artists\":[{\"id\":6452,\"name\":\"Jay Chou\"},{\"id\":6453,\"name\":\"Landy Wen\"}]"
                            + "}]},"
                            + "\"code\":200"
                            + "}");
        };
    }

    private Response response(Interceptor.Chain chain, int code, String body) throws IOException {
        return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code == 200 ? "OK" : "Upstream error")
                .body(ResponseBody.create(body, MediaType.get("application/json; charset=utf-8")))
                .build();
    }
}
