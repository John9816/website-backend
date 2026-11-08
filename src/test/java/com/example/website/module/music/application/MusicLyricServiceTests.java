package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.PerformanceMetrics;
import com.example.website.module.music.api.dto.LyricInfo;
import com.example.website.module.music.api.dto.LyricView;
import com.example.website.module.music.api.dto.MusicQuality;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.PlayInfo;
import com.example.website.module.music.api.dto.SongSearchItem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicLyricServiceTests {

    private final QqMusicClient qq = mock(QqMusicClient.class);
    private final NeteaseMusicClient netease = mock(NeteaseMusicClient.class);
    private final KuwoMusicClient kuwo = mock(KuwoMusicClient.class);
    private final TuneFreePayClient tfPay = mock(TuneFreePayClient.class);
    private final QqTextFallbackClient qqTextFallback = mock(QqTextFallbackClient.class);
    private final AtomicLong now = new AtomicLong(1000L);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private MusicCache cache;
    private MusicLyricService service;

    @BeforeEach
    void setUp() {
        cache = new MusicCache(new PerformanceMetrics(), now::get);
        service = service(cache);
    }

    @Test
    void concurrentMissesShareOneOfficialRequestAndReturnTheSameWordLyrics() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        when(qq.fetchLyrics("mid")).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return words();
        });
        try {
            List<Future<LyricView>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(2, TimeUnit.SECONDS));
                    return service.lyric("qq", "mid");
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            release.countDown();
            for (Future<LyricView> result : results) {
                assertEquals("[1000,500](1000,500,0)你好", result.get(2, TimeUnit.SECONDS).getKaraokeLyrics());
            }
            verify(qq, times(1)).fetchLyrics("mid");
            assertEquals(6.0, registry.get("music.lyrics").tag("granularity", "word").counter().count());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cacheIsRecheckedWhenAnotherFlightCompletesAfterTheInitialMiss() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch officialEntered = new CountDownLatch(1);
        CountDownLatch officialRelease = new CountDownLatch(1);
        CountDownLatch lateMissObserved = new CountDownLatch(1);
        CountDownLatch lateReadRelease = new CountDownLatch(1);
        AtomicInteger lateReads = new AtomicInteger();
        MusicCache racingCache = new MusicCache(new PerformanceMetrics(), now::get) {
            @Override
            public <T> T get(String key, Class<T> type) {
                T observed = super.get(key, type);
                if (Thread.currentThread().getName().equals("late-lyric-reader")
                        && key.equals("music:lyric:official:v4:qq:mid")
                        && lateReads.incrementAndGet() == 1) {
                    lateMissObserved.countDown();
                    try {
                        assertTrue(lateReadRelease.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                }
                return observed;
            }
        };
        MusicLyricService racingService = service(racingCache);
        when(qq.fetchLyrics("mid")).thenAnswer(invocation -> {
            officialEntered.countDown();
            assertTrue(officialRelease.await(2, TimeUnit.SECONDS));
            return words();
        });
        java.util.concurrent.CompletableFuture<LyricView> late = new java.util.concurrent.CompletableFuture<>();
        Thread lateThread = new Thread(() -> {
            try {
                late.complete(racingService.lyric("qq", "mid"));
            } catch (Throwable error) {
                late.completeExceptionally(error);
            }
        }, "late-lyric-reader");
        try {
            Future<LyricView> leader = executor.submit(() -> racingService.lyric("qq", "mid"));
            assertTrue(officialEntered.await(2, TimeUnit.SECONDS));
            lateThread.start();
            assertTrue(lateMissObserved.await(2, TimeUnit.SECONDS));
            officialRelease.countDown();
            assertTrue(leader.get(2, TimeUnit.SECONDS).getOfficial());
            lateReadRelease.countDown();
            assertEquals(words().getKaraokeLyrics(), late.get(2, TimeUnit.SECONDS).getKaraokeLyrics());
            verify(qq, times(1)).fetchLyrics("mid");
        } finally {
            officialRelease.countDown();
            lateReadRelease.countDown();
            executor.shutdownNow();
            lateThread.join(2000L);
        }
    }

    @Test
    void failedOfficialUsesCachedFallbackDuringCooldownThenRecoversWordLyrics() {
        SongSearchItem meta = metadata();
        when(qq.fetchLyrics("mid")).thenThrow(lyricFailure()).thenReturn(words());
        when(qq.fetchSongInfo("mid")).thenReturn(meta);
        when(qqTextFallback.fetchLyric(meta)).thenReturn(new LyricInfo("[00:01.000]备用", null));

        LyricView first = service.lyric("qq", "mid");
        assertEquals("cyapi", first.getProvider());
        assertFalse(first.getOfficial());
        assertNull(first.getKaraokeLyrics());
        now.addAndGet(29_000L);
        assertSame(first, service.lyric("qq", "mid"));
        verify(qq, times(1)).fetchLyrics("mid");
        now.addAndGet(2_000L);
        LyricView recovered = service.lyric("qq", "mid");
        assertTrue(recovered.getOfficial());
        assertEquals(words().getKaraokeLyrics(), recovered.getKaraokeLyrics());
        verify(qq, times(2)).fetchLyrics("mid");
        verify(qqTextFallback, times(1)).fetchLyric(meta);
    }

    @Test
    void totalFailuresAreSuppressedBrieflyAndFallbackCanRecoverBeforeOfficialCooldownEnds() {
        SongSearchItem meta = metadata();
        when(qq.fetchLyrics("mid")).thenThrow(lyricFailure());
        when(qq.fetchSongInfo("mid")).thenReturn(meta);
        when(qqTextFallback.fetchLyric(meta)).thenThrow(lyricFailure())
                .thenReturn(new LyricInfo("[00:01.000]备用恢复", null));

        assertEquals(MusicErrorCode.UPSTREAM_LYRIC_FAILED,
                assertThrows(MusicBusinessException.class, () -> service.lyric("qq", "mid")).getErrorCode());
        assertThrows(MusicBusinessException.class, () -> service.lyric("qq", "mid"));
        verify(qqTextFallback, times(1)).fetchLyric(meta);
        now.addAndGet(6_000L);
        assertEquals("[00:01.000]备用恢复", service.lyric("qq", "mid").getLineLyrics());
        verify(qq, times(1)).fetchLyrics("mid");
        verify(qqTextFallback, times(2)).fetchLyric(meta);
    }

    @Test
    void ordinaryLyricsExpireInTenMinutesAndWordLyricsInSixHoursIgnoringOldCache() {
        cache.put("music:lyric:official:v2:qq:mid", new LyricView("mid", MusicSource.QQ, "stale", null, "qq", true), 21600L);
        when(qq.fetchLyrics("mid")).thenReturn(new LyricInfo("[00:01.000]普通", null), words(),
                new LyricInfo("[00:01.000]刷新", null));
        assertEquals("[00:01.000]普通", service.lyric("qq", " mid ").getLineLyrics());
        now.addAndGet(9 * 60_000L);
        assertNull(service.lyric("qq", "mid").getKaraokeLyrics());
        now.addAndGet(2 * 60_000L);
        assertEquals(words().getKaraokeLyrics(), service.lyric("qq", "mid").getKaraokeLyrics());
        now.addAndGet(5 * 60 * 60_000L);
        assertEquals(words().getKaraokeLyrics(), service.lyric("qq", "mid").getKaraokeLyrics());
        now.addAndGet(61 * 60_000L);
        assertEquals("[00:01.000]刷新", service.lyric("qq", "mid").getLineLyrics());
        verify(qq, times(3)).fetchLyrics("mid");
    }

    @Test
    void fallbackExpiresInTenMinutesAndOfficialAlwaysGetsFirstChanceAfterCooldown() {
        SongSearchItem meta = metadata();
        when(qq.fetchLyrics("mid")).thenThrow(lyricFailure());
        when(qq.fetchSongInfo("mid")).thenReturn(meta);
        when(qqTextFallback.fetchLyric(meta)).thenReturn(new LyricInfo("备用一", null), new LyricInfo("备用二", null));
        assertEquals("备用一", service.lyric("qq", "mid").getLineLyrics());
        now.addAndGet(9 * 60_000L);
        assertEquals("备用一", service.lyric("qq", "mid").getLineLyrics());
        now.addAndGet(2 * 60_000L);
        assertEquals("备用二", service.lyric("qq", "mid").getLineLyrics());
        verify(qq, times(3)).fetchLyrics("mid");
        verify(qqTextFallback, times(2)).fetchLyric(meta);
    }

    @Test
    void allUpstreamStagesShareTheDeadlineAndExpiredBudgetStopsFurtherFallbackWork() {
        SongSearchItem meta = metadata();
        AtomicLong officialDeadline = new AtomicLong();
        AtomicLong metadataDeadline = new AtomicLong();
        AtomicLong fallbackDeadline = new AtomicLong();
        when(qq.fetchLyrics("mid")).thenAnswer(invocation -> {
            officialDeadline.set(MusicRequestBudget.deadlineNanos());
            assertTrue(MusicRequestBudget.remainingNanos() <= TimeUnit.SECONDS.toNanos(8));
            throw lyricFailure();
        });
        when(qq.fetchSongInfo("mid")).thenAnswer(invocation -> {
            metadataDeadline.set(MusicRequestBudget.deadlineNanos());
            return meta;
        });
        when(qqTextFallback.fetchLyric(meta)).thenAnswer(invocation -> {
            fallbackDeadline.set(MusicRequestBudget.deadlineNanos());
            return new LyricInfo("fallback", null);
        });
        assertEquals("fallback", service.lyric("qq", "mid").getLineLyrics());
        assertEquals(officialDeadline.get(), metadataDeadline.get());
        assertEquals(officialDeadline.get(), fallbackDeadline.get());
        assertEquals(Long.MAX_VALUE, MusicRequestBudget.remainingNanos());

        when(qq.fetchLyrics("expired")).thenAnswer(invocation -> {
            Thread.sleep(75L);
            throw lyricFailure();
        });
        try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(40, TimeUnit.MILLISECONDS)) {
            assertEquals(MusicErrorCode.UPSTREAM_TIMEOUT,
                    assertThrows(MusicBusinessException.class, () -> service.lyric("qq", "expired")).getErrorCode());
        }
        verify(qq, never()).fetchSongInfo("expired");
    }

    @Test
    void followerWaitUsesItsRemainingBudgetWithoutCancellingSharedLeader() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(qq.fetchLyrics("mid")).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return words();
        });
        try {
            Future<LyricView> leader = executor.submit(() -> service.lyric("qq", "mid"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();
            try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(50, TimeUnit.MILLISECONDS)) {
                assertEquals(MusicErrorCode.UPSTREAM_TIMEOUT,
                        assertThrows(MusicBusinessException.class, () -> service.lyric("qq", "mid")).getErrorCode());
            }
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
            release.countDown();
            assertTrue(leader.get(2, TimeUnit.SECONDS).getOfficial());
            assertTrue(service.lyric("qq", "mid").getOfficial());
            verify(qq, times(1)).fetchLyrics("mid");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void interruptedFollowerKeepsItsInterruptFlagAndLeavesSharedLeaderRunning() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch followerStarted = new CountDownLatch(1);
        java.util.concurrent.CompletableFuture<MusicErrorCode> followerResult = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        when(qq.fetchLyrics("mid")).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return words();
        });
        Thread follower = new Thread(() -> {
            followerStarted.countDown();
            try {
                service.lyric("qq", "mid");
                followerResult.complete(null);
            } catch (MusicBusinessException ex) {
                interrupted.set(Thread.currentThread().isInterrupted());
                followerResult.complete(ex.getErrorCode());
            } catch (Throwable error) {
                followerResult.completeExceptionally(error);
            }
        }, "interrupted-lyric-follower");
        try {
            Future<LyricView> leader = executor.submit(() -> service.lyric("qq", "mid"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            follower.start();
            assertTrue(followerStarted.await(2, TimeUnit.SECONDS));
            follower.interrupt();
            assertEquals(MusicErrorCode.UPSTREAM_TIMEOUT, followerResult.get(1, TimeUnit.SECONDS));
            assertTrue(interrupted.get());
            assertFalse(leader.isDone());
            release.countDown();
            assertTrue(leader.get(2, TimeUnit.SECONDS).getOfficial());
            verify(qq, times(1)).fetchLyrics("mid");
        } finally {
            release.countDown();
            executor.shutdownNow();
            follower.join(2000L);
        }
    }

    @Test
    void officialNeteaseAndKuwoRetainProviderAndTunefreeFallbackHasNoInventedWordTiming() {
        when(netease.fetchLyric("ne")).thenReturn(new String[]{"网易云", "逐字"});
        assertEquals("netease", service.lyric("netease", "ne").getProvider());
        when(kuwo.fetchLyrics("kw")).thenReturn(words());
        assertTrue(service.lyric("kuwo", "kw").getOfficial());
        when(kuwo.fetchLyrics("fallback")).thenThrow(lyricFailure());
        PlayInfo play = new PlayInfo();
        play.setLyric(new LyricInfo("酷我备用", null));
        when(tfPay.play(MusicSource.KUWO, "fallback", MusicQuality.FLAC)).thenReturn(play);
        LyricView fallback = service.lyric("kuwo", "fallback");
        assertEquals("tunefree", fallback.getProvider());
        assertFalse(fallback.getOfficial());
        assertNull(fallback.getKaraokeLyrics());
    }

    @Test
    void lateOfficialResultIsDiscardedAndCanRecoverAfterCooldown() {
        when(qq.fetchLyrics("late")).thenAnswer(invocation -> {
            Thread.sleep(75L);
            return words();
        }).thenReturn(words());
        try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(40, TimeUnit.MILLISECONDS)) {
            assertEquals(MusicErrorCode.UPSTREAM_TIMEOUT,
                    assertThrows(MusicBusinessException.class, () -> service.lyric("qq", "late")).getErrorCode());
        }
        assertThrows(MusicBusinessException.class, () -> service.lyric("qq", "late"));
        now.addAndGet(31_000L);
        assertTrue(service.lyric("qq", "late").getOfficial());
        verify(qq, times(2)).fetchLyrics("late");
        assertEquals(2.0, registry.get("music.requests").tag("outcome", "timeout").counter().count());
    }

    @Test
    void emptyOfficialResponseProducesABusinessFailureAndRetriesAfterCooldown() {
        when(netease.fetchLyric("empty")).thenReturn(null, new String[0], new String[]{"recovered", null});
        assertEquals(MusicErrorCode.UPSTREAM_LYRIC_FAILED,
                assertThrows(MusicBusinessException.class, () -> service.lyric("netease", "empty")).getErrorCode());
        assertThrows(MusicBusinessException.class, () -> service.lyric("netease", "empty"));
        now.addAndGet(31_000L);
        assertThrows(MusicBusinessException.class, () -> service.lyric("netease", "empty"));
        now.addAndGet(31_000L);
        assertEquals("recovered", service.lyric("netease", "empty").getLineLyrics());
        verify(netease, times(3)).fetchLyric("empty");
    }

    @Test
    void invalidSourceAndEmptyIdDoNotReachUpstream() {
        assertEquals(MusicErrorCode.INVALID_SOURCE,
                assertThrows(MusicBusinessException.class, () -> service.lyric("unknown", "mid")).getErrorCode());
        assertEquals(MusicErrorCode.SONG_NOT_FOUND,
                assertThrows(MusicBusinessException.class, () -> service.lyric("qq", " ")).getErrorCode());
        verifyNoInteractions(qq, netease, kuwo, tfPay, qqTextFallback);
    }

    private MusicLyricService service(MusicCache musicCache) {
        return new MusicLyricService(musicCache, qq, netease, kuwo, tfPay, qqTextFallback, new MusicMetrics(registry));
    }

    private static LyricInfo words() {
        return new LyricInfo("[00:01.000]你好", "[1000,500](1000,500,0)你好");
    }

    private static SongSearchItem metadata() {
        SongSearchItem song = new SongSearchItem();
        song.setId("mid");
        song.setSource(MusicSource.QQ);
        song.setName("歌曲");
        song.setArtist("歌手");
        return song;
    }

    private static MusicBusinessException lyricFailure() {
        return new MusicBusinessException(MusicErrorCode.UPSTREAM_LYRIC_FAILED, "controlled lyric failure");
    }
}
