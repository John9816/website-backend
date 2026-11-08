package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.LyricInfo;
import com.example.website.module.music.api.dto.AlbumDetailView;
import com.example.website.module.music.api.dto.ArtistWorksView;
import com.example.website.module.music.api.dto.LyricView;
import com.example.website.module.music.api.dto.MusicQuality;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.NeteasePlaybackQuality;
import com.example.website.module.music.api.dto.PlayInfo;
import com.example.website.module.music.api.dto.PlaylistListView;
import com.example.website.module.music.api.dto.SongSearchItem;
import com.example.website.module.settings.application.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MusicServiceTests {

    @Mock
    private QqMusicClient qq;

    @Mock
    private NeteaseMusicClient netease;

    @Mock
    private ChkszNeteaseMusicClient chkszNetease;

    @Mock
    private KuwoMusicClient kuwo;

    @Mock
    private TuneFreePayClient tfPay;

    @Mock
    private QqTextFallbackClient qqTextFallback;

    @Mock
    private PlayUrlVerifier playUrlVerifier;

    @Mock
    private SysConfigService configService;

    private MusicService musicService;

    @BeforeEach
    void setUp() {
        lenient().when(configService.getValue(MusicService.CFG_PLAY_RESOLVER_ORDER)).thenReturn(Optional.empty());
        lenient().when(configService.getValue(MusicService.CFG_CROSS_SOURCE_ORDER)).thenReturn(Optional.empty());
        lenient().when(qq.resolvePlay(anyString(), any(MusicQuality.class)))
                .thenThrow(new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL, "resolver unavailable"));
        musicService = MusicTestServices.create(new MusicCache(), configService, qq, netease, kuwo,
                chkszNetease, tfPay, qqTextFallback, playUrlVerifier);
    }


    @Test
    void ordinaryLyricCacheRetriesWordTimingAfterShortExpiryAndIgnoresOldEntries() {
        AtomicLong now = new AtomicLong(1000);
        MusicCache cache = new MusicCache(new com.example.website.config.PerformanceMetrics(), now::get);
        cache.put("music:lyric:official:v2:qq:mid", new LyricView("mid", MusicSource.QQ, "old", null, "qq", true), 21600);
        musicService = MusicTestServices.create(cache, configService, qq, netease, kuwo,
                chkszNetease, tfPay, qqTextFallback, playUrlVerifier);
        LyricInfo ordinary = new LyricInfo("[00:01.000]你好", null);
        LyricInfo enhanced = new LyricInfo("[00:01.000]你好", "[1000,500](1000,500,0)你好");
        when(qq.fetchLyrics("mid")).thenReturn(ordinary, enhanced);
        assertEquals(ordinary.getLineLyrics(), musicService.lyric("qq", "mid").getLineLyrics());
        now.addAndGet(9 * 60 * 1000);
        assertNull(musicService.lyric("qq", "mid").getKaraokeLyrics());
        now.addAndGet(2 * 60 * 1000);
        assertEquals(enhanced.getKaraokeLyrics(), musicService.lyric("qq", "mid").getKaraokeLyrics());
        now.addAndGet(60 * 60 * 1000);
        assertEquals(enhanced.getKaraokeLyrics(), musicService.lyric("qq", "mid").getKaraokeLyrics());
        verify(qq, times(2)).fetchLyrics("mid");
    }

    @Test
    void officialWordLyricsAreReturnedAndCachedForBothProviders() {
        LyricInfo both = new LyricInfo("[00:01.000]你好", "[1000,500](1000,500,0)你好");
        when(qq.fetchLyrics("mid")).thenReturn(both);
        when(kuwo.fetchLyrics("228908")).thenReturn(both);
        for (String source : Arrays.asList("qq", "kuwo")) {
            String id = source.equals("qq") ? "mid" : "228908";
            LyricView lyric = musicService.lyric(source, id);
            assertEquals(both.getKaraokeLyrics(), lyric.getKaraokeLyrics());
            assertEquals(both.getLineLyrics(), lyric.getLineLyrics());
            assertTrue(lyric.getOfficial());
            assertEquals(source, lyric.getProvider());
            assertEquals(lyric, musicService.lyric(source, id));
        }
        verify(qq, times(1)).fetchLyrics("mid");
        verify(kuwo, times(1)).fetchLyrics("228908");
    }

    @Test
    void lyricUsesOfficialQqEndpointEvenWhenPlayCacheContainsFallbackLyric() {
        SongSearchItem meta = song("song-mid", "track-one", "artist-one");
        PlayInfo fallback = new PlayInfo();
        fallback.setId("song-mid");
        fallback.setSource(MusicSource.QQ);
        fallback.setActualSource(MusicSource.QQ);
        fallback.setName("track-one");
        fallback.setArtist("artist-one");
        fallback.setAlbum("album-one");
        fallback.setPlayUrl("https://example.com/fallback.m4a");
        fallback.setActualQuality("320k");
        fallback.setLyric(new LyricInfo("[00:00.00]line-one", null));

        when(tfPay.play(MusicSource.QQ, "song-mid", MusicQuality.K128))
                .thenThrow(new MusicBusinessException(MusicErrorCode.MISSING_UPSTREAM_TOKEN, "token missing"));
        when(qq.fetchSongInfo("song-mid")).thenReturn(meta);
        when(qqTextFallback.fetchSongUrl("song-mid", MusicQuality.K128, meta)).thenReturn(fallback);
        when(qq.fetchLyrics("song-mid")).thenReturn(new LyricInfo("[00:00.00]official-line", null));

        PlayInfo play = musicService.play("qq", "song-mid", "128k");

        assertEquals("song-mid", play.getId());
        assertEquals(MusicSource.QQ, play.getSource());
        assertEquals(MusicSource.QQ, play.getActualSource());
        assertEquals("https://example.com/fallback.m4a", play.getPlayUrl());
        assertEquals("128k", play.getRequestedQuality());
        assertEquals("320k", play.getActualQuality());
        assertEquals("[00:00.00]line-one", play.getLyric().getLineLyrics());

        LyricView lyric = musicService.lyric("qq", "song-mid");
        assertEquals("[00:00.00]official-line", lyric.getLineLyrics());
        assertNull(lyric.getKaraokeLyrics());
        assertEquals("qq", lyric.getProvider());
        assertTrue(lyric.getOfficial());
        verify(qq).fetchLyrics("song-mid");
        verify(qqTextFallback, never()).fetchLyric(meta);
    }

    @Test
    void lyricFallsBackToQqTextLookupWhenOfficialLyricFails() {
        SongSearchItem meta = song("song-mid", "track-two", "artist-two");

        when(qq.fetchLyrics("song-mid"))
                .thenThrow(new MusicBusinessException(MusicErrorCode.UPSTREAM_LYRIC_FAILED, "qq lyric failed"));
        when(qq.fetchSongInfo("song-mid")).thenReturn(meta);
        when(qqTextFallback.fetchLyric(meta)).thenReturn(new LyricInfo("[00:01.00]line-two", null));

        LyricView lyric = musicService.lyric("qq", "song-mid");

        assertEquals("song-mid", lyric.getId());
        assertEquals(MusicSource.QQ, lyric.getSource());
        assertEquals("[00:01.00]line-two", lyric.getLineLyrics());
        assertNull(lyric.getKaraokeLyrics());
        assertEquals("cyapi", lyric.getProvider());
        assertFalse(lyric.getOfficial());
    }

    @Test
    void kuwoLyricUsesOfficialEndpointBeforeTuneFreeFallback() {
        when(kuwo.fetchLyrics("631473518")).thenReturn(new LyricInfo("[00:00.00]official-kuwo", null));

        LyricView lyric = musicService.lyric("kuwo", "631473518");

        assertEquals("[00:00.00]official-kuwo", lyric.getLineLyrics());
        assertEquals("kuwo", lyric.getProvider());
        assertTrue(lyric.getOfficial());
        verify(tfPay, never()).play(MusicSource.KUWO, "631473518", MusicQuality.FLAC);
    }

    @Test
    void kuwoLyricFallsBackToTuneFreeWhenOfficialEndpointFails() {
        PlayInfo fallback = new PlayInfo();
        fallback.setLyric(new LyricInfo("[00:00.00]fallback-kuwo", null));
        when(kuwo.fetchLyrics("631473518"))
                .thenThrow(new MusicBusinessException(MusicErrorCode.UPSTREAM_LYRIC_FAILED, "kuwo failed"));
        when(tfPay.play(MusicSource.KUWO, "631473518", MusicQuality.FLAC)).thenReturn(fallback);

        LyricView lyric = musicService.lyric("kuwo", "631473518");

        assertEquals("[00:00.00]fallback-kuwo", lyric.getLineLyrics());
        assertEquals("tunefree", lyric.getProvider());
        assertFalse(lyric.getOfficial());
    }

    @Test
    void playFallsBackToQqTextResolverWhenPrimaryQqUrlFailsProbe() {
        SongSearchItem meta = song("song-mid", "track-three", "artist-three");

        PlayInfo primary = new PlayInfo();
        primary.setId("song-mid");
        primary.setSource(MusicSource.QQ);
        primary.setActualSource(MusicSource.QQ);
        primary.setName("track-three");
        primary.setArtist("artist-three");
        primary.setPlayUrl("http://wx.music.tc.qq.com/M500songmid.mp3?vkey=bad");
        primary.setActualQuality("128k");

        PlayInfo fallback = new PlayInfo();
        fallback.setId("song-mid");
        fallback.setSource(MusicSource.QQ);
        fallback.setActualSource(MusicSource.QQ);
        fallback.setName("track-three");
        fallback.setArtist("artist-three");
        fallback.setPlayUrl("https://cdn.cyapi.top/fallback-song.mid");
        fallback.setActualQuality("320k");

        when(tfPay.play(MusicSource.QQ, "song-mid", MusicQuality.K128)).thenReturn(primary);
        when(playUrlVerifier.isPlayable(primary.getPlayUrl())).thenReturn(false);
        when(qq.fetchSongInfo("song-mid")).thenReturn(meta);
        when(qqTextFallback.fetchSongUrl("song-mid", MusicQuality.K128, meta)).thenReturn(fallback);

        PlayInfo play = musicService.play("qq", "song-mid", "128k");

        assertEquals("https://cdn.cyapi.top/fallback-song.mid", play.getPlayUrl());
        assertEquals("128k", play.getRequestedQuality());
        assertEquals("320k", play.getActualQuality());
        verify(chkszNetease, never()).resolve(anyString(), any(NeteasePlaybackQuality.class));
    }

    @Test
    void qqDefaultsToXunhuisiHighQualityBeforeTuneFree() {
        PlayInfo primary = new PlayInfo();
        primary.setId("song-mid");
        primary.setSource(MusicSource.QQ);
        primary.setActualSource(MusicSource.QQ);
        primary.setPlayUrl("https://isure6.stream.qqmusic.qq.com/C600song-mid.m4a?vkey=test");
        primary.setActualQuality("320k");
        doReturn(primary).when(qq).resolvePlay("song-mid", MusicQuality.K320);
        when(playUrlVerifier.isPlayable(primary.getPlayUrl())).thenReturn(true);

        PlayInfo play = musicService.play("qq", "song-mid", null);

        assertEquals(primary.getPlayUrl(), play.getPlayUrl());
        assertEquals("320k", play.getRequestedQuality());
        assertEquals("320k", play.getActualQuality());
        verify(tfPay, never()).play(any(MusicSource.class), anyString(), any(MusicQuality.class));
        verify(qq).resolvePlay("song-mid", MusicQuality.K320);
    }

    @Test
    void neteaseUsesChkszBeforeTuneFreeForJymaster() {
        PlayInfo preferred = new PlayInfo();
        preferred.setId("2034742057");
        preferred.setSource(MusicSource.NETEASE);
        preferred.setActualSource(MusicSource.NETEASE);
        preferred.setPlayUrl("https://cdn.example.com/master.flac");
        preferred.setActualQuality("jymaster");
        when(chkszNetease.resolve("2034742057", NeteasePlaybackQuality.JYMASTER)).thenReturn(preferred);

        PlayInfo play = musicService.play("netease", "2034742057", "jymaster");

        assertEquals("https://cdn.example.com/master.flac", play.getPlayUrl());
        assertEquals("jymaster", play.getRequestedQuality());
        assertEquals("jymaster", play.getActualQuality());
        verify(tfPay, never()).play(any(MusicSource.class), anyString(), any(MusicQuality.class));
    }

    @Test
    void neteaseFallsBackToMappedTuneFreeQualityWhenChkszFails() {
        when(chkszNetease.resolve("2034742057", NeteasePlaybackQuality.HIRES))
                .thenThrow(new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED, "chksz unavailable"));
        PlayInfo fallback = new PlayInfo();
        fallback.setId("2034742057");
        fallback.setSource(MusicSource.NETEASE);
        fallback.setActualSource(MusicSource.NETEASE);
        fallback.setPlayUrl("https://cdn.example.com/fallback.flac");
        fallback.setActualQuality("flac24bit");
        when(tfPay.play(MusicSource.NETEASE, "2034742057", MusicQuality.FLAC24)).thenReturn(fallback);

        PlayInfo play = musicService.play("netease", "2034742057", "hires");

        assertEquals("https://cdn.example.com/fallback.flac", play.getPlayUrl());
        assertEquals("hires", play.getRequestedQuality());
        assertEquals("flac24bit", play.getActualQuality());
        verify(tfPay).play(MusicSource.NETEASE, "2034742057", MusicQuality.FLAC24);
    }

    @Test
    void neteaseLegacyAliasSharesCanonicalCacheAndPreservesRequestedValue() {
        PlayInfo preferred = new PlayInfo();
        preferred.setId("2034742057");
        preferred.setSource(MusicSource.NETEASE);
        preferred.setActualSource(MusicSource.NETEASE);
        preferred.setPlayUrl("https://cdn.example.com/lossless.flac");
        preferred.setActualQuality("lossless");
        when(chkszNetease.resolve("2034742057", NeteasePlaybackQuality.LOSSLESS)).thenReturn(preferred);

        PlayInfo canonical = musicService.play("netease", "2034742057", "lossless");
        PlayInfo legacy = musicService.play("netease", "2034742057", "flac");

        assertEquals("lossless", canonical.getRequestedQuality());
        assertEquals("flac", legacy.getRequestedQuality());
        assertTrue(Boolean.TRUE.equals(legacy.getFromCache()));
        verify(chkszNetease).resolve("2034742057", NeteasePlaybackQuality.LOSSLESS);
    }

    @Test
    void simultaneousCanonicalAndLegacyQualityRequestsKeepIndependentResponses() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        PlayInfo preferred = new PlayInfo();
        preferred.setId("2034742057");
        preferred.setSource(MusicSource.NETEASE);
        preferred.setActualSource(MusicSource.NETEASE);
        preferred.setPlayUrl("https://cdn.example.com/lossless.flac");
        preferred.setActualQuality("lossless");
        when(chkszNetease.resolve("2034742057", NeteasePlaybackQuality.LOSSLESS)).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return preferred;
        });
        ExecutorService leader = Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicReference<Thread> followerThread = new java.util.concurrent.atomic.AtomicReference<>();
        ExecutorService follower = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task);
            followerThread.set(thread);
            return thread;
        });
        try {
            java.util.concurrent.Future<PlayInfo> canonical = leader.submit(() -> musicService.play("netease", "2034742057", "lossless"));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            java.util.concurrent.Future<PlayInfo> legacy = follower.submit(() -> musicService.play("netease", "2034742057", "flac"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                Thread thread = followerThread.get();
                waiting = thread.getState() == Thread.State.TIMED_WAITING
                        && Arrays.stream(thread.getStackTrace()).anyMatch(frame -> frame.getClassName().endsWith("PerformanceSingleFlight"));
                if (waiting) break;
                Thread.yield();
            }
            assertTrue(waiting, "follower must join the active single flight");
            release.countDown();
            PlayInfo first = canonical.get(2, TimeUnit.SECONDS);
            PlayInfo second = legacy.get(2, TimeUnit.SECONDS);
            assertEquals("lossless", first.getRequestedQuality());
            assertEquals("flac", second.getRequestedQuality());
            org.junit.jupiter.api.Assertions.assertNotSame(first, second);
            assertFalse(Boolean.TRUE.equals(first.getFromCache()));
            assertFalse(Boolean.TRUE.equals(second.getFromCache()));
            verify(chkszNetease).resolve("2034742057", NeteasePlaybackQuality.LOSSLESS);
        } finally {
            release.countDown();
            leader.shutdownNow();
            follower.shutdownNow();
        }
    }

    @Test
    void playInvalidatesCachedQqUrlBeforeReturningIt() {
        MusicCache cache = new MusicCache();
        musicService = MusicTestServices.create(cache, configService, qq, netease, kuwo,
                chkszNetease, tfPay, qqTextFallback, playUrlVerifier);

        PlayInfo cached = new PlayInfo();
        cached.setId("song-mid");
        cached.setSource(MusicSource.QQ);
        cached.setActualSource(MusicSource.QQ);
        cached.setName("cached-track");
        cached.setArtist("cached-artist");
        cached.setPlayUrl("http://wx.music.tc.qq.com/M500cached.mp3?vkey=stale");
        cached.setActualQuality("128k");
        cache.put("music:play:qq:song-mid:128k", cached, 60);

        PlayInfo fresh = new PlayInfo();
        fresh.setId("song-mid");
        fresh.setSource(MusicSource.QQ);
        fresh.setActualSource(MusicSource.QQ);
        fresh.setName("fresh-track");
        fresh.setArtist("fresh-artist");
        fresh.setPlayUrl("https://cdn.cyapi.top/fresh-song.mid");
        fresh.setActualQuality("128k");

        when(playUrlVerifier.isPlayable(cached.getPlayUrl())).thenReturn(false);
        when(tfPay.play(MusicSource.QQ, "song-mid", MusicQuality.K128)).thenReturn(fresh);

        PlayInfo play = musicService.play("qq", "song-mid", "128k");

        assertEquals("https://cdn.cyapi.top/fresh-song.mid", play.getPlayUrl());
        assertEquals("128k", play.getRequestedQuality());
        assertFalse(Boolean.TRUE.equals(play.getFromCache()));
    }

    @Test
    void playReusesCachedQqHighResolverUrlForExplicitFlac() {
        MusicCache cache = new MusicCache();
        musicService = MusicTestServices.create(cache, configService, qq, netease, kuwo,
                chkszNetease, tfPay, qqTextFallback, playUrlVerifier);

        PlayInfo cached = new PlayInfo();
        cached.setId("song-mid");
        cached.setSource(MusicSource.QQ);
        cached.setActualSource(MusicSource.QQ);
        cached.setPlayUrl("https://isure6.stream.qqmusic.qq.com/C600song-mid.m4a?vkey=test");
        cached.setActualQuality("320k");
        cache.put("music:play:qq:song-mid:flac", cached, 60);
        when(playUrlVerifier.isPlayable(cached.getPlayUrl())).thenReturn(true);

        PlayInfo play = musicService.play("qq", "song-mid", "flac");

        assertEquals(cached.getPlayUrl(), play.getPlayUrl());
        assertEquals(MusicSource.QQ, play.getActualSource());
        assertEquals("flac", play.getRequestedQuality());
        assertEquals("320k", play.getActualQuality());
        assertTrue(Boolean.TRUE.equals(play.getFromCache()));
        verify(qq, never()).resolvePlay(anyString(), any(MusicQuality.class));
    }

    @Test
    void configuredResolverOrderIsReadFromSysConfig() {
        when(configService.getValue(MusicService.CFG_PLAY_RESOLVER_ORDER))
                .thenReturn(Optional.of("qq_text"));
        SongSearchItem meta = song("song-mid", "configured", "resolver");
        when(qq.fetchSongInfo("song-mid")).thenReturn(meta);
        PlayInfo fallback = playInfo(MusicSource.QQ, "song-mid", "https://cdn.example.com/configured.m4a");
        when(qqTextFallback.fetchSongUrl("song-mid", MusicQuality.K128, meta)).thenReturn(fallback);

        PlayInfo result = musicService.play("qq", "song-mid", "128k");

        assertEquals(fallback.getPlayUrl(), result.getPlayUrl());
        verify(qq, never()).resolvePlay(anyString(), any(MusicQuality.class));
    }

    @Test
    void nullTuneFreeResultIsReportedAsNoPlayableUrl() {
        when(configService.getValue(MusicService.CFG_PLAY_RESOLVER_ORDER))
                .thenReturn(Optional.of("primary"));
        doReturn(null).when(tfPay).play(MusicSource.QQ, "song-mid", MusicQuality.K128);

        MusicBusinessException error = org.junit.jupiter.api.Assertions.assertThrows(
                MusicBusinessException.class,
                () -> musicService.play("qq", "song-mid", "128k"));

        assertEquals(MusicErrorCode.NO_PLAYABLE_URL, error.getErrorCode());
    }

    @Test
    void configuredCrossSourceOrderSelectsFirstConfiguredMatch() {
        when(configService.getValue(MusicService.CFG_PLAY_RESOLVER_ORDER))
                .thenReturn(Optional.of("cross_source"));
        when(configService.getValue(MusicService.CFG_CROSS_SOURCE_ORDER))
                .thenReturn(Optional.of("kuwo,qq,netease"));
        SongSearchItem meta = song("netease-id", "same song", "same artist");
        when(netease.fetchSongInfo("netease-id")).thenReturn(meta);
        SongSearchItem wrongArtist = song("wrong-id", "same song", "different artist");
        SongSearchItem hit = song("kuwo-id", "same song", "same artist");
        when(kuwo.search("same song same artist", 1, 3)).thenReturn(Arrays.asList(wrongArtist, hit));
        PlayInfo fallback = playInfo(MusicSource.KUWO, "kuwo-id", "https://cdn.example.com/kuwo.flac");
        when(tfPay.play(MusicSource.KUWO, "kuwo-id", MusicQuality.FLAC)).thenReturn(fallback);

        PlayInfo result = musicService.play("netease", "netease-id", "lossless");

        assertEquals(MusicSource.KUWO, result.getActualSource());
        assertEquals("netease-id", result.getId());
        verify(qq, never()).search(anyString(), anyInt(), anyInt());
    }

    @Test
    void crossSourcePreservesActualRecordingIdentityInResponseAndCache() {
        when(configService.getValue(MusicService.CFG_PLAY_RESOLVER_ORDER)).thenReturn(Optional.of("cross_source"));
        when(configService.getValue(MusicService.CFG_CROSS_SOURCE_ORDER)).thenReturn(Optional.of("kuwo"));
        SongSearchItem original = song("original", "晴天", "周杰伦");
        original.setDurationSec(269);
        SongSearchItem match = song("228908", "晴天", "周杰伦");
        match.setDurationSec(270);
        when(netease.fetchSongInfo("original")).thenReturn(original);
        when(kuwo.search("晴天 周杰伦", 1, 3)).thenReturn(Collections.singletonList(match));
        when(tfPay.play(MusicSource.KUWO, "228908", MusicQuality.FLAC))
                .thenReturn(playInfo(MusicSource.KUWO, "228908", "https://cdn.example.com/match.flac"));
        for (int i = 0; i < 2; i++) {
            PlayInfo result = musicService.play("netease", "original", "lossless");
            com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(result);
            assertEquals("original", result.getId());
            assertEquals(MusicSource.NETEASE, result.getSource());
            assertEquals("228908", json.path("actualId").asText());
            assertEquals("exact", json.path("lyricMatch").asText());
        }
    }

    @Test
    void crossSourceRejectsDifferentRecordingVersionsAndDurations() {
        when(configService.getValue(MusicService.CFG_PLAY_RESOLVER_ORDER)).thenReturn(Optional.of("cross_source"));
        when(configService.getValue(MusicService.CFG_CROSS_SOURCE_ORDER)).thenReturn(Optional.of("kuwo"));
        SongSearchItem original = song("original", "晴天", "周杰伦");
        original.setDurationSec(269);
        when(netease.fetchSongInfo("original")).thenReturn(original);
        for (String title : Arrays.asList("晴天 (Live)", "晴天（现场版）", "晴天 [Remix]", "晴天")) {
            SongSearchItem wrong = song("wrong", title, "周杰伦");
            wrong.setDurationSec(title.equals("晴天") ? 320 : 269);
            when(kuwo.search("晴天 周杰伦", 1, 3)).thenReturn(Collections.singletonList(wrong));
            lenient().when(tfPay.play(MusicSource.KUWO, "wrong", MusicQuality.FLAC))
                    .thenReturn(playInfo(MusicSource.KUWO, "wrong", "https://cdn.example.com/wrong.flac"));
            org.junit.jupiter.api.Assertions.assertThrows(MusicBusinessException.class,
                    () -> musicService.play("netease", "original", "lossless"), title);
        }
        verify(tfPay, never()).play(any(MusicSource.class), anyString(), any(MusicQuality.class));
    }

    @Test
    void textResolutionDoesNotClaimKnownLyricIdentity() {
        when(configService.getValue(MusicService.CFG_PLAY_RESOLVER_ORDER)).thenReturn(Optional.of("qq_text"));
        SongSearchItem meta = song("mid", "晴天", "周杰伦");
        when(qq.fetchSongInfo("mid")).thenReturn(meta);
        when(qqTextFallback.fetchSongUrl("mid", MusicQuality.K128, meta))
                .thenReturn(playInfo(MusicSource.QQ, "mid", "https://cdn.example.com/text.mp3"));
        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(musicService.play("qq", "mid", "128k"));
        assertEquals("unverified", json.path("lyricMatch").asText());
        assertTrue(json.path("actualId").isNull());
    }

    @Test
    void crossSourceDoesNotAcceptTitleOnlyOrFirstResultMatch() {
        when(configService.getValue(MusicService.CFG_PLAY_RESOLVER_ORDER))
                .thenReturn(Optional.of("cross_source"));
        when(configService.getValue(MusicService.CFG_CROSS_SOURCE_ORDER))
                .thenReturn(Optional.of("kuwo"));
        SongSearchItem meta = song("netease-id", "same song", "right artist");
        when(netease.fetchSongInfo("netease-id")).thenReturn(meta);
        when(kuwo.search("same song right artist", 1, 3)).thenReturn(Arrays.asList(
                song("wrong-artist", "same song", "different artist"),
                song("wrong-title", "another song", "right artist")));

        org.junit.jupiter.api.Assertions.assertThrows(MusicBusinessException.class,
                () -> musicService.play("netease", "netease-id", "lossless"));

        verify(tfPay, never()).play(any(MusicSource.class), anyString(), any(MusicQuality.class));
    }

    @Test
    void fourSlowPrimariesLeaveExecutionCapacityForFastFallbacks() throws Exception {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor resolverPool =
                new com.example.website.config.OkHttpConfig().musicResolverExecutor();
        ExecutorService callers = Executors.newFixedThreadPool(4);
        CountDownLatch primaries = new CountDownLatch(4), release = new CountDownLatch(1);
        when(configService.getValue(MusicService.CFG_PLAY_RESOLVER_ORDER)).thenReturn(Optional.of("primary,qq_text"));
        doAnswer(call -> {
            primaries.countDown();
            try { release.await(4, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT);
        }).when(qq).resolvePlay(anyString(), any(MusicQuality.class));
        when(qq.fetchSongInfo(anyString())).thenAnswer(call -> song(call.getArgument(0), "track", "artist"));
        when(qqTextFallback.fetchSongUrl(anyString(), any(MusicQuality.class), any()))
                .thenAnswer(call -> playInfo(MusicSource.QQ, call.getArgument(0), "https://cdn.example.com/fast.mp3"));
        musicService = MusicTestServices.create(new MusicCache(), configService, qq, netease, kuwo,
                chkszNetease, tfPay, qqTextFallback, playUrlVerifier, resolverPool, 100);
        java.util.List<java.util.concurrent.Future<PlayInfo>> results = new java.util.ArrayList<>();
        try {
            for (int n = 0; n < 4; n++) {
                String id = "slow-" + n;
                results.add(callers.submit(() -> musicService.play("qq", id, "128k")));
            }
            assertTrue(primaries.await(1, TimeUnit.SECONDS));
            for (java.util.concurrent.Future<PlayInfo> result : results)
                assertEquals("https://cdn.example.com/fast.mp3", result.get(1, TimeUnit.SECONDS).getPlayUrl());
        } finally {
            release.countDown(); callers.shutdownNow(); resolverPool.shutdown();
            callers.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectedResolverReturnsServiceBusyInsteadOfInternalError() {
        musicService = MusicTestServices.create(new MusicCache(), configService, qq, netease, kuwo,
                chkszNetease, tfPay, qqTextFallback, playUrlVerifier,
                task -> { throw new java.util.concurrent.RejectedExecutionException("full"); }, 50);
        MusicBusinessException error = org.junit.jupiter.api.Assertions.assertThrows(MusicBusinessException.class,
                () -> musicService.play("qq", "mid", "128k"));
        assertEquals(503, error.getErrorCode().getHttpStatus().value());
    }

    @Test
    void hedgesSlowPrimaryAndCancelsItAfterFallbackWins() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch primaryStarted = new CountDownLatch(1);
        CountDownLatch primaryInterrupted = new CountDownLatch(1);
        try {
            doAnswer(invocation -> {
                primaryStarted.countDown();
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException ex) {
                    primaryInterrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT, "cancelled loser", ex);
                }
                return playInfo(MusicSource.QQ, "song-mid", "https://cdn.example.com/slow.m4a");
            }).when(qq).resolvePlay("song-mid", MusicQuality.K128);
            SongSearchItem meta = song("song-mid", "hedged", "fallback");
            when(qq.fetchSongInfo("song-mid")).thenReturn(meta);
            PlayInfo fallback = playInfo(MusicSource.QQ, "song-mid", "https://cdn.example.com/fast.m4a");
            when(qqTextFallback.fetchSongUrl("song-mid", MusicQuality.K128, meta)).thenReturn(fallback);
            musicService = MusicTestServices.create(new MusicCache(), configService, qq, netease, kuwo,
                    chkszNetease, tfPay, qqTextFallback, playUrlVerifier, executor, 50L);

            long started = System.nanoTime();
            PlayInfo result = musicService.play("qq", "song-mid", "128k");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(primaryStarted.await(1, TimeUnit.SECONDS));
            assertEquals(fallback.getPlayUrl(), result.getPlayUrl());
            assertTrue(elapsedMillis < 1_000L, "hedged fallback should not wait for the slow primary");
            assertTrue(primaryInterrupted.await(1, TimeUnit.SECONDS), "losing resolver should be interrupted");
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void neteaseUsesShorterHedgeBeforeSlowPrimaryFinishes() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch primaryStarted = new CountDownLatch(1);
        CountDownLatch primaryInterrupted = new CountDownLatch(1);
        try {
            when(configService.getValue(MusicService.CFG_CROSS_SOURCE_ORDER))
                    .thenReturn(Optional.of("kuwo"));
            doAnswer(invocation -> {
                primaryStarted.countDown();
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException ex) {
                    primaryInterrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT, "cancelled loser", ex);
                }
                return playInfo(MusicSource.NETEASE, "netease-id", "https://cdn.example.com/slow.mp3");
            }).when(chkszNetease).resolve("netease-id", NeteasePlaybackQuality.EXHIGH);

            SongSearchItem meta = song("netease-id", "hedged song", "hedged artist");
            SongSearchItem match = song("kuwo-id", "hedged song", "hedged artist");
            when(netease.fetchSongInfo("netease-id")).thenReturn(meta);
            when(kuwo.search("hedged song hedged artist", 1, 3))
                    .thenReturn(Collections.singletonList(match));
            PlayInfo fallback = playInfo(MusicSource.KUWO, "kuwo-id", "https://cdn.example.com/fast.mp3");
            when(tfPay.play(MusicSource.KUWO, "kuwo-id", MusicQuality.K320)).thenReturn(fallback);

            musicService = MusicTestServices.create(new MusicCache(), configService, qq, netease, kuwo,
                    chkszNetease, tfPay, qqTextFallback, playUrlVerifier, executor, 1_200L);

            long started = System.nanoTime();
            PlayInfo result = musicService.play("netease", "netease-id", "320k");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(primaryStarted.await(1, TimeUnit.SECONDS));
            assertEquals(fallback.getPlayUrl(), result.getPlayUrl());
            assertTrue(elapsedMillis < 1_100L,
                    "netease fallback should start before the global 1200ms hedge delay");
            assertTrue(primaryInterrupted.await(1, TimeUnit.SECONDS), "losing primary should be interrupted");
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void playCacheExpiresBeforeUpstreamUrl() {
        AtomicLong clock = new AtomicLong(1_000L);
        MusicCache cache = new MusicCache(new com.example.website.config.PerformanceMetrics(), clock::get);
        musicService = MusicTestServices.create(cache, configService, qq, netease, kuwo,
                chkszNetease, tfPay, qqTextFallback, playUrlVerifier);
        PlayInfo expiring = playInfo(MusicSource.QQ, "song-mid", "https://cdn.example.com/expiring.m4a");
        expiring.setExpireSec(90);
        doReturn(expiring).when(qq).resolvePlay("song-mid", MusicQuality.K128);

        musicService.play("qq", "song-mid", "128k");
        clock.addAndGet(TimeUnit.SECONDS.toMillis(59));
        assertTrue(Boolean.TRUE.equals(musicService.play("qq", "song-mid", "128k").getFromCache()));
        clock.addAndGet(TimeUnit.SECONDS.toMillis(2));
        musicService.play("qq", "song-mid", "128k");

        verify(qq, times(2)).resolvePlay("song-mid", MusicQuality.K128);
    }

    @Test
    void albumDetailRoutesToSourceAndCachesPage() {
        AlbumDetailView expected = new AlbumDetailView("album-mid", MusicSource.QQ, "Album", null,
                null, "Artist", "2026-08-05", 1, 20, 1, Collections.emptyList());
        when(qq.fetchAlbumDetail("album-mid", 1, 20)).thenReturn(expected);

        assertEquals(expected, musicService.albumDetail("qq", "album-mid", 1, 20));
        assertEquals(expected, musicService.albumDetail("qq", "album-mid", 1, 20));

        verify(qq).fetchAlbumDetail("album-mid", 1, 20);
    }

    private PlayInfo playInfo(MusicSource source, String id, String url) {
        PlayInfo info = new PlayInfo();
        info.setSource(source);
        info.setActualSource(source);
        info.setId(id);
        info.setPlayUrl(url);
        info.setActualQuality(MusicQuality.K128.getValue());
        return info;
    }

    @Test
    void recommendedPlaylistsRouteToEverySourceAndCacheResults() {
        PlaylistListView qqView = new PlaylistListView(MusicSource.QQ, "recommend", "recommend", 1, 20, 0L,
                Collections.emptyList());
        PlaylistListView neteaseView = new PlaylistListView(MusicSource.NETEASE, "recommend", "recommend", 1, 20, 0L,
                Collections.emptyList());
        PlaylistListView kuwoView = new PlaylistListView(MusicSource.KUWO, "recommend", "recommend", 1, 20, 0L,
                Collections.emptyList());
        when(qq.fetchRecommendedPlaylists(20)).thenReturn(qqView);
        when(netease.fetchRecommendedPlaylists(20)).thenReturn(neteaseView);
        when(kuwo.fetchRecommendedPlaylists(20)).thenReturn(kuwoView);

        assertEquals(qqView, musicService.recommendedPlaylists("qq", 20));
        assertEquals(neteaseView, musicService.recommendedPlaylists("netease", 20));
        assertEquals(kuwoView, musicService.recommendedPlaylists("kuwo", 20));
        assertEquals(qqView, musicService.recommendedPlaylists("qq", 20));

        verify(qq).fetchRecommendedPlaylists(20);
        verify(netease).fetchRecommendedPlaylists(20);
        verify(kuwo).fetchRecommendedPlaylists(20);
    }

    @Test
    void artistWorksRoutesAlbumTypeToKuwo() {
        ArtistWorksView expected = new ArtistWorksView("336", MusicSource.KUWO, "album", 1, 20,
                45, Collections.emptyList(), Collections.emptyList());
        when(kuwo.fetchArtistWorks("336", "album", 1, 20)).thenReturn(expected);

        assertEquals(expected, musicService.artistWorks("kuwo", "336", "ALBUM", 1, 20));

        verify(kuwo).fetchArtistWorks("336", "album", 1, 20);
    }

    private SongSearchItem song(String id, String name, String artist) {
        SongSearchItem item = new SongSearchItem();
        item.setId(id);
        item.setSource(MusicSource.QQ);
        item.setName(name);
        item.setArtist(artist);
        return item;
    }
}
