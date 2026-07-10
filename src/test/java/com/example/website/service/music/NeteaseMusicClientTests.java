package com.example.website.service.music;

import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.SongSearchItem;
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
                            + "\"artists\":[{\"name\":\"Jay Chou\"},{\"name\":\"Landy Wen\"}]"
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
