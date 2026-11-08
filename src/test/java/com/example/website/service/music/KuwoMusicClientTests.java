package com.example.website.service.music;

import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.AlbumDetailView;
import com.example.website.dto.music.ArtistDetailView;
import com.example.website.dto.music.ArtistWorksView;
import com.example.website.dto.music.PlaylistDetailView;
import com.example.website.dto.music.PlaylistListView;
import com.example.website.dto.music.SongSearchItem;
import com.example.website.dto.music.ToplistDetailView;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KuwoMusicClientTests {


    @Test
    void enhancedLyricsUseSameSongIdAndNormalizeWordTiming() throws Exception {
        byte[] payload = WordLyricCodecTests.kuwoEnvelope("[kuwo:034]\n[00:01.000]<3200,-3200>你<1600,0>好");
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            assertEquals("newlyric.kuwo.cn", chain.request().url().host());
            byte[] query = java.util.Base64.getDecoder().decode(chain.request().url().query());
            byte[] key = "yeelion".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            for (int i = 0; i < query.length; i++) query[i] ^= key[i % key.length];
            assertTrue(new String(query, java.nio.charset.StandardCharsets.UTF_8).contains("rid=MUSIC_228908&lrcx=1"));
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(payload, MediaType.get("application/octet-stream"))).build();
        }).build();
        com.example.website.dto.music.LyricInfo lyric = new KuwoMusicClient(client, new ObjectMapper()).fetchLyrics("228908");
        assertEquals("[1000,500](1000,400,0)你(1400,100,0)好", lyric.getKaraokeLyrics());
        assertEquals("[00:01.000]你好", lyric.getLineLyrics());
    }

    @Test
    void enhancedFailureFallsBackToOrdinaryKuwoLyrics() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            if (chain.request().url().host().equals("newlyric.kuwo.cn")) throw new IOException("timeout");
            return response(chain, "{\"code\":200,\"data\":{\"lrclist\":[{\"time\":\"1\",\"lineLyric\":\"普通歌词\"}]}}");
        }).build();
        com.example.website.dto.music.LyricInfo lyric = new KuwoMusicClient(client, new ObjectMapper()).fetchLyrics("228908");
        assertEquals("[00:01.00]普通歌词", lyric.getLineLyrics());
        org.junit.jupiter.api.Assertions.assertNull(lyric.getKaraokeLyrics());
    }

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
        assertEquals("5678", song.getArtistId());
        assertEquals(Collections.singletonList("5678"), song.getArtistIds());
        assertEquals("Ye Hui Mei", song.getAlbum());
        assertEquals("https://img1.kuwo.cn/star/albumcover/500/s3s94/93/211513640.jpg", song.getCoverUrl());
        assertEquals(269, song.getDurationSec());
    }

    @ParameterizedTest
    @MethodSource("songArtworkCases")
    void songArtworkUsesAlbumResolutionWithoutExtraRequests(String fields, String expected) {
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            return response(chain, "{\"abslist\":[{\"DC_TARGETID\":\"228908\","
                    + "\"NAME\":\"Song\"," + fields + "}]}");
        }).build();

        List<SongSearchItem> songs = new KuwoMusicClient(client, new ObjectMapper()).search("song", 1, 10);

        assertEquals(expected, songs.get(0).getCoverUrl());
        assertEquals(1, requests.get(), "Selecting album artwork must not add per-song network requests");
    }

    private static Stream<Arguments> songArtworkCases() {
        return Stream.of(
                Arguments.of("\"web_albumpic_short\":\"120/album.jpg\",\"MVPIC\":\"324/video.jpg\"",
                        "https://img1.kuwo.cn/star/albumcover/500/album.jpg"),
                Arguments.of("\"web_albumpic_short\":\"120/album.jpg\"",
                        "https://img1.kuwo.cn/star/albumcover/500/album.jpg"),
                Arguments.of("\"albumpic\":\"http://img2.kuwo.cn/star/albumcover/120/album.jpg?version=1\"",
                        "http://img2.kuwo.cn/star/albumcover/500/album.jpg?version=1"),
                Arguments.of("\"pic\":\"https://img1.kwcdn.kuwo.cn/star/albumcover/120/album.jpg\"",
                        "https://img1.kwcdn.kuwo.cn/star/albumcover/500/album.jpg"),
                Arguments.of("\"albumpic\":\"https://img2.kuwo.cn/star/albumcover/1000/album.jpg\","
                                + "\"web_albumpic_short\":\"120/album.jpg\"",
                        "https://img2.kuwo.cn/star/albumcover/1000/album.jpg"),
                Arguments.of("\"web_albumpic_short\":\"500/album.jpg\"",
                        "https://img1.kuwo.cn/star/albumcover/500/album.jpg"),
                Arguments.of("\"albumpic\":\"https://example.com/star/albumcover/120/album.jpg\"",
                        "https://example.com/star/albumcover/120/album.jpg"),
                Arguments.of("\"pic\":\"https://img.kuwo.cn.example.com/star/albumcover/120/album.jpg\"",
                        "https://img.kuwo.cn.example.com/star/albumcover/120/album.jpg"),
                Arguments.of("\"web_albumpic_short\":\"\",\"hts_MVPIC\":\"https://img4.kuwo.cn/wmvpic/324/video.jpg\"",
                        "https://img4.kuwo.cn/wmvpic/324/video.jpg"),
                Arguments.of("\"MVPIC\":\"324/video.jpg\"",
                        "https://img1.kuwo.cn/wmvpic/324/video.jpg"),
                Arguments.of("\"web_artistpic_short\":\"120/artist.jpg\"",
                        "https://img1.kuwo.cn/star/starheads/120/artist.jpg"),
                Arguments.of("\"ALBUM\":\"Missing artwork\"", null));
    }

    @Test
    void playlistSongsPreferAlbumArtworkOverVideoThumbnails() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> response(chain,
                "{\"name\":\"Playlist\",\"pic\":\"https://img/playlist.jpg\",\"total\":1,\"musiclist\":[{"
                        + "\"id\":\"228908\",\"name\":\"Song\",\"artist\":\"A\",\"artistid\":\"336\","
                        + "\"hts_MVPIC\":\"https://img4.kuwo.cn/wmvpic/324/video.jpg\","
                        + "\"web_albumpic_short\":\"120/album.jpg\"}]}"))
                .build();

        PlaylistDetailView playlist = new KuwoMusicClient(client, new ObjectMapper())
                .fetchPlaylistDetail("1", 1, 10);

        assertEquals("https://img1.kuwo.cn/star/albumcover/500/album.jpg", playlist.getList().get(0).getCoverUrl());
    }

    @Test
    void recommendedPlaylistsUseRcmFeed() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            assertTrue(chain.request().url().encodedPath().contains("/classify/playlist/getRcmPlayList"));
            assertEquals("1", chain.request().url().queryParameter("pn"));
            assertEquals("6", chain.request().url().queryParameter("rn"));
            return response(chain, "{\"code\":200,\"data\":{\"total\":9075,\"data\":[{"
                    + "\"id\":\"3601256457\",\"name\":\"Recommended\",\"img\":\"https://img/playlist.jpg\","
                    + "\"uname\":\"Creator\",\"total\":\"42\",\"listencnt\":\"48439\"}]}}" );
        }).build();
        KuwoMusicClient kuwo = new KuwoMusicClient(client, new ObjectMapper());

        PlaylistListView view = kuwo.fetchRecommendedPlaylists(6);

        assertEquals(MusicSource.KUWO, view.getSource());
        assertEquals("recommend", view.getCategory());
        assertEquals(9075L, view.getTotal());
        assertEquals("3601256457", view.getList().get(0).getId());
        assertEquals(42, view.getList().get(0).getTrackCount());
        assertEquals(48439L, view.getList().get(0).getPlayCount());
    }

    @Test
    void toplistDetailMapsDelimitedArtistIds() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> response(chain,
                        "{\"name\":\"New\",\"musiclist\":[{"
                                + "\"id\":\"631473518\",\"name\":\"Song\",\"artist\":\"A / B\","
                                + "\"artistid\":\"5438944,5438945\"}]}"))
                .build();
        KuwoMusicClient kuwo = new KuwoMusicClient(client, new ObjectMapper());

        ToplistDetailView detail = kuwo.fetchToplistDetail("17", 1, 3);

        assertEquals(Arrays.asList("5438944", "5438945"), detail.getList().get(0).getArtistIds());
        assertEquals("5438944", detail.getList().get(0).getArtistId());
    }

    @Test
    void toplistDetailResolvesMissingCoverFromOfficialKuwoImageService() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    if ("artistpicserver.kuwo.cn".equals(chain.request().url().host())) {
                        assertEquals("rid_pic", chain.request().url().queryParameter("type"));
                        assertEquals("631473518", chain.request().url().queryParameter("rid"));
                        return response(chain,
                                "http://img4.kuwo.cn/pic_music/500/s4s11/91/190348087.jpg");
                    }
                    return response(chain, "{\"name\":\"New\",\"musiclist\":[{"
                            + "\"id\":\"631473518\",\"name\":\"Song\","
                            + "\"artist\":\"A\",\"artistid\":\"5438944\"}]}");
                })
                .build();
        KuwoMusicClient kuwo = new KuwoMusicClient(client, new ObjectMapper());

        ToplistDetailView detail = kuwo.fetchToplistDetail("17", 1, 3);

        assertEquals("http://img4.kuwo.cn/pic_music/500/s4s11/91/190348087.jpg",
                detail.getList().get(0).getCoverUrl());
    }

    @Test
    void playlistDetailMapsNestedArtistId() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> response(chain,
                        "{\"musiclist\":[{"
                                + "\"id\":\"631473518\",\"name\":\"Song\","
                                + "\"artist\":{\"id\":\"5438944\",\"name\":\"A\"}}]}"))
                .build();
        KuwoMusicClient kuwo = new KuwoMusicClient(client, new ObjectMapper());

        PlaylistDetailView detail = kuwo.fetchPlaylistDetail("3601256457", 1, 3);

        assertEquals(Collections.singletonList("5438944"), detail.getList().get(0).getArtistIds());
        assertEquals("5438944", detail.getList().get(0).getArtistId());
    }

    @Test
    void playlistDetailBackfillsMissingArtistIdByExactName() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    if (chain.request().url().encodedPath().contains("/r.s")) {
                        return response(chain, "{\"abslist\":[{\"ARTISTID\":\"5438944\",\"ARTIST\":\"A\"}]}");
                    }
                    return response(chain, "{\"musiclist\":[{"
                            + "\"id\":\"631473518\",\"name\":\"Song\",\"artist\":\"A\"}]}" );
                })
                .build();
        KuwoMusicClient kuwo = new KuwoMusicClient(client, new ObjectMapper());

        PlaylistDetailView detail = kuwo.fetchPlaylistDetail("3601256457", 1, 3);

        assertEquals(Collections.singletonList("5438944"), detail.getList().get(0).getArtistIds());
    }

    @Test
    void fetchLyricUsesOfficialKuwoWebEndpointAndBuildsLrc() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    assertTrue(chain.request().url().encodedPath().contains("/openapi/v1/www/lyric/getlyric"));
                    assertEquals("631473518", chain.request().url().queryParameter("musicId"));
                    return response(chain, "{\"code\":200,\"data\":{\"lrclist\":["
                            + "{\"lineLyric\":\"line-one\",\"time\":\"0.0\"},"
                            + "{\"lineLyric\":\"line-two\",\"time\":\"61.53\"}]}}");
                })
                .build();
        KuwoMusicClient kuwo = new KuwoMusicClient(client, new ObjectMapper());

        String lyric = kuwo.fetchLyric("631473518");

        assertEquals("[00:00.00]line-one\n[01:01.53]line-two", lyric);
    }

    @Test
    void albumDetailAcceptsOfficialSingleQuotedPayloadAndMapsAllArtistIds() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> response(chain,
                "{'albumid':'1293','name':'Album','artist':'A&B','artistid':'336','songnum':'1',"
                        + "'hts_img':'https://img.kuwo.cn/album.jpg','pub':'2003-07-31','musiclist':[{"
                        + "'musicrid':'228908','name':'Track','artist':'A&B','allartistid':'336,337',"
                        + "'album':'Album','albumId':1293,'duration':'269','N_MINFO':'bitrate:128,format:mp3;bitrate:320,format:mp3;format:flac'}]}"))
                .build();
        KuwoMusicClient kuwo = new KuwoMusicClient(client, new ObjectMapper());

        AlbumDetailView album = kuwo.fetchAlbumDetail("1293", 1, 20);

        assertEquals("https://img.kuwo.cn/album.jpg", album.getCoverUrl());
        assertEquals("1293", album.getList().get(0).getAlbumId());
        assertEquals(Arrays.asList("336", "337"), album.getList().get(0).getArtistIds());
        assertEquals(Arrays.asList("128k", "320k", "flac"), album.getList().get(0).getAvailableQualities());
    }

    @Test
    void artistAlbumWorksMapsOfficialAlbumList() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> response(chain,
                "{'total':'45','albumlist':[{'albumid':'1293','name':'Album','artist':'Artist',"
                        + "'artistid':'336','hts_img':'https://img.kuwo.cn/album.jpg','pub':'2003-07-31','musiccnt':'11'}]}"))
                .build();
        KuwoMusicClient kuwo = new KuwoMusicClient(client, new ObjectMapper());

        ArtistWorksView works = kuwo.fetchArtistWorks("336", "album", 1, 20);

        assertEquals(45, works.getTotal());
        assertEquals("1293", works.getAlbums().get(0).getId());
        assertEquals("336", works.getAlbums().get(0).getArtistId());
    }

    @Test
    void artistDetailRejectsMismatchedArtistInfoAndResolvesNameFromWorks() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            String path = chain.request().url().encodedPath();
            if (path.contains("/artist/artistInfo")) {
                return response(chain, "{\"code\":200,\"data\":{\"artistList\":[{"
                        + "\"id\":336,\"name\":\"周杰伦\",\"musicNum\":1698,\"albumNum\":45}]}}");
            }
            if ("artist2music".equals(chain.request().url().queryParameter("stype"))) {
                assertEquals("195793", chain.request().url().queryParameter("artistid"));
                return response(chain, "{'artist':'周深','total':'1507','musiclist':[]}");
            }
            assertEquals("artist", chain.request().url().queryParameter("ft"));
            assertEquals("周深", chain.request().url().queryParameter("all"));
            return response(chain, "{\"abslist\":[{\"ARTISTID\":\"195793\",\"ARTIST\":\"周深\","
                    + "\"SONGNUM\":\"1507\",\"ALBUMNUM\":\"83\","
                    + "\"hts_PICPATH\":\"https://img.kuwo.cn/zhoushen.jpg\",\"desc\":\"周深简介\"}]}");
        }).build();
        KuwoMusicClient kuwo = new KuwoMusicClient(client, new ObjectMapper());

        ArtistDetailView detail = kuwo.fetchArtistDetail("195793");

        assertEquals("195793", detail.getId());
        assertEquals("周深", detail.getName());
        assertEquals("https://img.kuwo.cn/zhoushen.jpg", detail.getCoverUrl());
        assertEquals("周深简介", detail.getDescription());
        assertEquals(1507, detail.getSongCount());
        assertEquals(83, detail.getAlbumCount());
    }

    @Test
    void artistDetailKeepsVerifiedWorksInfoWhenSearchEnrichmentFails() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            String path = chain.request().url().encodedPath();
            if (path.contains("/artist/artistInfo")) {
                return response(chain, "{\"code\":200,\"data\":{\"artistList\":[]}}");
            }
            if ("artist2music".equals(chain.request().url().queryParameter("stype"))) {
                return response(chain, "{'artist':'周深','total':'1507','musiclist':[]}");
            }
            throw new IOException("artist search offline");
        }).build();
        KuwoMusicClient kuwo = new KuwoMusicClient(client, new ObjectMapper());

        ArtistDetailView detail = kuwo.fetchArtistDetail("195793");

        assertEquals("周深", detail.getName());
        assertEquals(1507, detail.getSongCount());
    }

    private Interceptor fakeKuwoSearch() {
        return chain -> response(chain,
                "{"
                        + "\"abslist\":[{"
                        + "\"DC_TARGETID\":\"228908\","
                        + "\"MUSICRID\":\"MUSIC_228908\","
                        + "\"NAME\":\"Sunny Day\","
                        + "\"ARTIST\":\"Jay Chou\","
                        + "\"ARTISTID\":\"5678\","
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
