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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KuwoMusicClientTests {

    @Test
    void searchBuildsCoverUrlFromKuwoSearchFields() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(fakeKuwoSearch())
                .build();
        KuwoMusicClient kuwo = new KuwoMusicClient(client, new ObjectMapper());

        List<SongSearchItem> songs = kuwo.search("jay", 1, 10);

        assertEquals(1, songs.size());
        SongSearchItem song = songs.get(0);
        assertEquals(MusicSource.KUWO, song.getSource());
        assertEquals("228908", song.getId());
        assertEquals("Sunny Day", song.getName());
        assertEquals("Jay Chou", song.getArtist());
        assertEquals("Ye Hui Mei", song.getAlbum());
        assertEquals("https://img4.kuwo.cn/wmvpic/324/s4s75/52/1458871193.jpg", song.getCoverUrl());
        assertEquals(269, song.getDurationSec());
    }

    private Interceptor fakeKuwoSearch() {
        return chain -> response(chain,
                "{"
                        + "\"abslist\":[{"
                        + "\"DC_TARGETID\":\"228908\","
                        + "\"MUSICRID\":\"MUSIC_228908\","
                        + "\"NAME\":\"Sunny Day\","
                        + "\"ARTIST\":\"Jay Chou\","
                        + "\"ALBUM\":\"Ye Hui Mei\","
                        + "\"DURATION\":\"269\","
                        + "\"hts_MVPIC\":\"https://img4.kuwo.cn/wmvpic/324/s4s75/52/1458871193.jpg\","
                        + "\"web_albumpic_short\":\"120/s3s94/93/211513640.jpg\","
                        + "\"N_MINFO\":\"level:p,bitrate:320,format:mp3,size:10.29Mb;level:h,bitrate:128,format:mp3,size:4.12Mb\""
                        + "}]"
                        + "}");
    }

    private Response response(Interceptor.Chain chain, String body) throws IOException {
        return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.get("application/json; charset=utf-8")))
                .build();
    }
}
