package com.example.website.service.music;

import com.example.website.dto.music.ArtistDetailView;
import com.example.website.dto.music.AlbumDetailView;
import com.example.website.dto.music.ArtistWorksView;
import com.example.website.dto.music.MusicQuality;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.PlayInfo;
import com.example.website.dto.music.PlaylistListView;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqMusicClientTests {

    @Test
    void fetchArtistDetailUsesCurrentMusicuDetailInfoMethod() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(fakeArtistDetail())
                .build();
        QqMusicClient qq = new QqMusicClient(client, new ObjectMapper());

        ArtistDetailView artist = qq.fetchArtistDetail("002Mp50T4HjMya");

        assertEquals(MusicSource.QQ, artist.getSource());
        assertEquals("002Mp50T4HjMya", artist.getId());
        assertEquals("梓渝", artist.getName());
        assertEquals(77, artist.getSongCount());
        assertEquals(26, artist.getAlbumCount());
        assertEquals("artist brief", artist.getDescription());
    }

    @Test
    void recommendedPlaylistsUseHotRecommendModuleAndRespectLimit() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            String body = bodyOf(chain.request());
            assertTrue(body.contains("playlist.HotRecommendServer"));
            assertTrue(body.contains("get_hot_recommend"));
            return response(chain, "{\"code\":0,\"req_1\":{\"code\":0,\"data\":{\"v_hot\":["
                    + "{\"content_id\":8075336924,\"title\":\"First\",\"cover\":\"http://qpic/first.jpg\","
                    + "\"username\":\"Creator\",\"listen_num\":145496069,\"rcmdtemplate\":\"编辑推荐\"},"
                    + "{\"content_id\":7981001167,\"title\":\"Second\"}]}}}" );
        }).build();
        QqMusicClient qq = new QqMusicClient(client, new ObjectMapper());

        PlaylistListView view = qq.fetchRecommendedPlaylists(1);

        assertEquals(MusicSource.QQ, view.getSource());
        assertEquals(1, view.getList().size());
        assertEquals("8075336924", view.getList().get(0).getId());
        assertEquals("https://qpic/first.jpg", view.getList().get(0).getCoverUrl());
        assertEquals("Creator", view.getList().get(0).getCreatorName());
        assertEquals(145496069L, view.getList().get(0).getPlayCount());
    }

    @Test
    void albumDetailUnwrapsSongInfoAndMapsOfficialQualities() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            String body = bodyOf(chain.request());
            assertTrue(body.contains("AlbumSongList"));
            assertTrue(body.contains("0035Y3b01o1BsG"));
            return response(chain, "{\"code\":0,\"req_1\":{\"code\":0,\"data\":{" 
                    + "\"totalNum\":1,\"songList\":[{\"songInfo\":{" 
                    + "\"mid\":\"0019tNGN1TJbLT\",\"title\":\"Track\",\"interval\":279,"
                    + "\"singer\":[{\"mid\":\"002Mp50T4HjMya\",\"name\":\"Artist\"}],"
                    + "\"album\":{\"mid\":\"0035Y3b01o1BsG\",\"name\":\"Album\",\"time_public\":\"2026-08-05\"},"
                    + "\"file\":{\"size_128mp3\":1,\"size_320mp3\":2,\"size_flac\":3,\"size_hires\":4}"
                    + "}}]}}}");
        }).build();
        QqMusicClient qq = new QqMusicClient(client, new ObjectMapper());

        AlbumDetailView album = qq.fetchAlbumDetail("0035Y3b01o1BsG", 1, 20);

        assertEquals("Album", album.getName());
        assertEquals("2026-08-05", album.getPublishTime());
        assertEquals("002Mp50T4HjMya", album.getList().get(0).getArtistId());
        assertEquals(4, album.getList().get(0).getAvailableQualities().size());
        assertEquals("flac24bit", album.getList().get(0).getAvailableQualities().get(3));
    }

    @Test
    void artistWorksUsesSingerDetailSongList() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            String body = bodyOf(chain.request());
            assertTrue(body.contains("get_singer_detail_info"));
            return response(chain, "{\"code\":0,\"singer\":{\"code\":0,\"data\":{" 
                    + "\"total_song\":1013,\"songlist\":[{\"mid\":\"song-mid\",\"title\":\"Track\","
                    + "\"singer\":[{\"mid\":\"artist-mid\",\"name\":\"Artist\"}]}]}}}");
        }).build();
        QqMusicClient qq = new QqMusicClient(client, new ObjectMapper());

        ArtistWorksView works = qq.fetchArtistWorks("artist-mid", "song", 1, 20);

        assertEquals(1013, works.getTotal());
        assertEquals("song-mid", works.getSongs().get(0).getId());
        assertEquals("artist-mid", works.getSongs().get(0).getArtistId());
    }

    @Test
    void resolverMapsPublicQualitiesAndParsesPlayInfo() {
        List<String> qualities = new ArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            qualities.add(chain.request().url().queryParameter("quality"));
            return response(chain, "{\"code\":200,\"msg\":\"解析成功\","
                    + "\"title\":\"Track\",\"singer\":\"Artist\","
                    + "\"cover\":\"http://cover/track.jpg\","
                    + "\"music_url\":\"https://cdn.example/track.m4a\","
                    + "\"quality\":\"M4A高品质(320kbps AAC)\","
                    + "\"lyric\":\"[00:00.00]Track\"}");
        }).build();
        QqMusicClient qq = new QqMusicClient(client, new ObjectMapper());

        PlayInfo standard = qq.resolvePlay("song-mid", MusicQuality.K128);
        qq.resolvePlay("song-mid", MusicQuality.K320);
        PlayInfo lossless = qq.resolvePlay("song-mid", MusicQuality.FLAC24);

        assertEquals(java.util.Arrays.asList("standard", "high", "high"), qualities);
        assertEquals("Track", standard.getName());
        assertEquals("https://cover/track.jpg", standard.getCoverUrl());
        assertEquals("320k", standard.getActualQuality());
        assertEquals("[00:00.00]Track", lossless.getLyric().getLineLyrics());
    }

    @Test
    void flacUsesHighOfficialStreamInsteadOfDecryptProxy() {
        List<String> qualities = new ArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            String quality = chain.request().url().queryParameter("quality");
            qualities.add(quality);
            return response(chain, "{\"code\":200,"
                    + "\"title\":\"贰拾肆\",\"singer\":\"梓渝\","
                    + "\"music_url\":\"https://isure6.stream.qqmusic.qq.com/C600003Agdhd2tM867.m4a?vkey=test\","
                    + "\"quality\":\"M4A高品质(320kbps AAC)\"}");
        }).build();
        QqMusicClient qq = new QqMusicClient(client, new ObjectMapper());

        PlayInfo play = qq.resolvePlay("003Agdhd2tM867", MusicQuality.FLAC);

        assertEquals(java.util.Collections.singletonList("high"), qualities);
        assertEquals("https://isure6.stream.qqmusic.qq.com/C600003Agdhd2tM867.m4a?vkey=test",
                play.getPlayUrl());
        assertEquals("flac", play.getRequestedQuality());
        assertEquals("320k", play.getActualQuality());
    }

    private Interceptor fakeArtistDetail() {
        return chain -> {
            String body = chain.request().body() == null ? "" : bodyOf(chain.request());
            assertTrue(body.contains("get_singer_detail_info"));
            assertTrue(body.contains("002Mp50T4HjMya"));
            return response(chain, "{"
                    + "\"code\":0,"
                    + "\"singer\":{"
                    + "\"code\":0,"
                    + "\"data\":{"
                    + "\"singer_info\":{\"mid\":\"002Mp50T4HjMya\",\"name\":\"梓渝\"},"
                    + "\"singer_brief\":\"artist brief\","
                    + "\"total_song\":77,\"total_album\":26"
                    + "}}}");
        };
    }

    private String bodyOf(okhttp3.Request request) throws IOException {
        okio.Buffer buffer = new okio.Buffer();
        request.body().writeTo(buffer);
        return buffer.readUtf8();
    }

    private Response response(Interceptor.Chain chain, String body) {
        return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.get("application/json; charset=utf-8")))
                .build();
    }
}
