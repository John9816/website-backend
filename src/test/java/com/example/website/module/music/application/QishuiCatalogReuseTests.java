package com.example.website.module.music.application;

import com.example.website.module.music.api.dto.*;
import com.example.website.module.settings.application.SysConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QishuiCatalogReuseTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger mediaCalls = new AtomicInteger();
    private static final String TRACK = "{\"base_info\":{\"id\":\"1\",\"name\":\"Original\",\"duration_ms\":90000},"
            + "\"related_info\":{\"artist_links\":[{\"id\":\"artist\",\"name\":\"Singer\"}],\"album_link\":{\"id\":\"album\",\"name\":\"Album\"}},"
            + "\"display_info\":{\"cover_url\":\"https://example.com/cover.jpg\",\"lyric_info\":{\"lyric_entity\":{\"type\":\"krc\",\"content\":\"[1000,500]<0,500,0>Hello\"}}}}";
    private final AtomicReference<String> catalogTrack = new AtomicReference<>(TRACK);
    private final AtomicReference<String> mediaTrack = new AtomicReference<>(TRACK);

    private QishuiMusicClient catalog() {
        return new QishuiMusicClient(new QishuiHttpClient(new OkHttpClient.Builder().addInterceptor(chain -> {
            String path = chain.request().url().encodedPath();
            if (path.endsWith("/token/get/")) return QishuiClientTests.response(chain,
                    "{\"err_no\":0,\"client_token\":\"test\",\"expires_time\":4102444800}");
            String t = catalogTrack.get(), data;
            if (path.endsWith("/search/")) data = "{\"result_groups\":[{\"id\":\"tracks\",\"has_more\":false,\"data\":[{\"entity\":{\"track\":" + t + "}}]}]}";
            else if (path.endsWith("/playlist/detail/")) data = "{\"playlist\":{\"base_info\":{\"id\":\"playlist\"}},\"media_entities\":[{\"track\":" + t + "}]}";
            else if (path.endsWith("/album/detail/")) data = "{\"album_info\":{\"base_info\":{\"id\":\"album\"}},\"tracks\":[" + t + "]}";
            else if (path.endsWith("/artist/tracks/")) data = "{\"tracks\":[" + t + "]}";
            else if (path.endsWith("/feed/song-tab/")) data = "{\"has_more\":true,\"items\":[{\"type\":\"track\",\"entity\":{\"media\":{\"media_type\":\"track\",\"track_entity\":" + t + "}}}]}";
            else {
                assertTrue(path.endsWith("/media/mget/"));
                mediaCalls.incrementAndGet();
                data = "{\"media_list\":[{\"track_entity\":" + mediaTrack.get() + "}]}";
            }
            return QishuiClientTests.response(chain, "{\"err_no\":0,\"data\":" + data + "}");
        }).build(), mapper), mapper);
    }

    private void browse(QishuiMusicClient c, String entry) {
        switch (entry) {
            case "search": c.search("song", 1, 10); break;
            case "playlist": c.fetchPlaylistDetail("playlist:owner:2", 1, 10); break;
            case "album": c.fetchAlbumDetail("album", 1, 10); break;
            case "artist": c.fetchArtistWorks("artist", "song", 1, 10); break;
            case "discovery": new QishuiFeedClient(c, mapper).discovery(); break;
            case "roaming":
                QishuiCredential credential = new QishuiCredential();
                credential.setDeviceId("test-device"); credential.setUserId("test-user");
                new QishuiFeedClient(c, mapper).songs(credential, "recommended", 1, 1, null); break;
            default: fail(entry);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"search", "playlist", "album", "artist", "discovery", "roaming"})
    void browsingReusesAlreadyReturnedLyricsAndMetadataWithoutAnotherUpstream(String entry) {
        QishuiMusicClient c = catalog();
        browse(c, entry);
        assertEquals("[00:01.000]Hello", c.fetchLyrics("1").getLineLyrics());
        SongSearchItem info = c.fetchSongInfo("1");
        assertEquals("Singer", info.getArtist());
        info.setName("caller mutation");
        assertEquals("Original", c.fetchSongInfo("1").getName());
        assertEquals(0, mediaCalls.get(), "catalog already returned the full media entity");
    }

    @Test void partialCatalogEntriesDoNotHideFullLyricsOrReplaceCompleteSnapshots() {
        QishuiMusicClient c = catalog();
        catalogTrack.set("{\"base_info\":{\"id\":\"1\",\"name\":\"Partial\"}}");
        browse(c, "search");
        assertNotNull(c.fetchLyrics("1").getKaraokeLyrics());
        assertEquals(1, mediaCalls.get());
        browse(c, "search");
        assertEquals("Original", c.fetchSongInfo("1").getName());
        assertEquals(1, mediaCalls.get());
    }

    @Test void lyricsAloneDoNotMakeIncompleteMetadataAuthoritative() throws Exception {
        QishuiMusicClient c = catalog();
        com.fasterxml.jackson.databind.node.ObjectNode partial = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(TRACK);
        partial.remove("related_info");
        catalogTrack.set(partial.toString());
        browse(c, "search");
        assertEquals("Singer", c.fetchSongInfo("1").getArtist());
        assertEquals(1, mediaCalls.get());
    }

    @Test void completeCatalogCanRepairAnEarlierMissingLyricSnapshotImmediately() {
        QishuiMusicClient c = catalog();
        mediaTrack.set(TRACK.replace("[1000,500]<0,500,0>Hello", ""));
        assertNull(c.fetchLyrics("1").getLineLyrics());
        browse(c, "search");
        assertEquals("[00:01.000]Hello", c.fetchLyrics("1").getLineLyrics());
        assertEquals(1, mediaCalls.get());
    }

    @Test void malformedCatalogLyricsCannotBreakPlaybackOrPoisonTheNextRead() {
        QishuiMusicClient c = catalog();
        catalogTrack.set(TRACK.replace("[1000,500]<0,500,0>Hello", "[9223372036854775807,1]<1,1,0>x"));
        browse(c, "search");
        PlayInfo play = new PlayInfo(); play.setId("1");
        assertDoesNotThrow(() -> c.enrichCachedPlayback(play));
        assertNull(play.getLyric());
        assertEquals(0, mediaCalls.get());
        assertEquals("[00:01.000]Hello", c.fetchLyrics("1").getLineLyrics());
        assertEquals(1, mediaCalls.get());
    }

    @Test void playbackIncludesCachedCatalogDataAndColdPlaybackNeverWaitsForMetadata() {
        QishuiMusicClient c = catalog();
        QishuiParseClient parser = mock(QishuiParseClient.class);
        PlayInfo ticket = new PlayInfo();
        ticket.setSource(MusicSource.QISHUI); ticket.setId("1");
        ticket.setPlayUrl("/api/public/music/qishui/stream?ticket=test");
        when(parser.play("1", MusicQuality.K320)).thenReturn(ticket);
        SysConfigService config = mock(SysConfigService.class);
        when(config.getValue(anyString())).thenReturn(Optional.empty());
        MusicService service = MusicTestServices.create(new MusicCache(), config,
                mock(QqMusicClient.class), mock(NeteaseMusicClient.class), mock(KuwoMusicClient.class),
                mock(ChkszNeteaseMusicClient.class), mock(TuneFreePayClient.class),
                mock(QqTextFallbackClient.class), mock(PlayUrlVerifier.class), Runnable::run, 0L, c, parser);
        assertNull(service.play("qs", "1", "320k").getLyric());
        assertEquals(0, mediaCalls.get(), "a cold play ticket must not wait for catalog I/O");
        browse(c, "search");
        PlayInfo warm = service.play("qs", "1", "320k");
        assertEquals("Original", warm.getName());
        assertEquals("[00:01.000]Hello", warm.getLyric().getLineLyrics());
        warm.getLyric().setLineLyrics("caller mutation");
        assertEquals("[00:01.000]Hello", service.play("qs", "1", "320k").getLyric().getLineLyrics());
        assertEquals(0, mediaCalls.get());
        verify(parser, times(1)).play("1", MusicQuality.K320);
    }
}
