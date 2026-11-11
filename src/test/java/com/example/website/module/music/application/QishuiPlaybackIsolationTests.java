package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.PlayInfo;
import com.example.website.module.music.application.QishuiStreamService;
import com.example.website.module.settings.application.SysConfigService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QishuiPlaybackIsolationTests {
    private final SysConfigService config = mock(SysConfigService.class);
    private final QishuiStreamTicket tickets = new QishuiStreamTicket("test-signing-key");

    private MusicService service(Executor executor, MusicCache cache) {
        return MusicTestServices.create(cache, config,
                mock(QqMusicClient.class), mock(NeteaseMusicClient.class), mock(KuwoMusicClient.class),
                mock(ChkszNeteaseMusicClient.class), mock(TuneFreePayClient.class),
                mock(QqTextFallbackClient.class), mock(PlayUrlVerifier.class), executor, 0L,
                mock(QishuiMusicClient.class), new QishuiParseClient(tickets, "", mock(QishuiStreamService.class)));
    }

    @Test void localTicketsRemainAvailableWhenTheSharedResolverExecutorRejectsWork() {
        when(config.getValue(anyString())).thenReturn(Optional.empty());
        AtomicLong now = new AtomicLong();
        MusicService service = service(task -> { throw new RejectedExecutionException("remote resolvers full"); },
                new MusicCache(new com.example.website.config.PerformanceMetrics(), now::get));
        PlayInfo first = assertDoesNotThrow(() -> service.play("qs", "track", null));
        QishuiStreamTicket.Payload payload = tickets.verify(first.getPlayUrl().split("ticket=", 2)[1]);
        assertEquals("track", payload.getTrackId());
        assertEquals("320k", payload.getQuality());
        assertEquals("exact", first.getLyricMatch());
        assertFalse(first.getFromCache());

        PlayInfo cached = service.play("qishui", "track", "320k");
        assertTrue(cached.getFromCache());
        assertEquals(first.getPlayUrl(), cached.getPlayUrl());
        now.set(TimeUnit.SECONDS.toMillis(1051));
        PlayInfo refreshed = service.play("qs", "track", "320k");
        assertFalse(refreshed.getFromCache());
        assertNotEquals(first.getPlayUrl(), refreshed.getPlayUrl(), "refresh before the signed ticket expiry");
    }

    @Test void localTicketsDoNotWaitBehindRunningRemoteResolvers() throws Exception {
        when(config.getValue(anyString())).thenReturn(Optional.empty());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        executor.execute(() -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            MusicService service = service(executor, new MusicCache());
            try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(500, TimeUnit.MILLISECONDS)) {
                PlayInfo play = assertDoesNotThrow(() -> service.play("qs", "queued-track", "flac"));
                assertEquals("flac", tickets.verify(play.getPlayUrl().split("ticket=", 2)[1]).getQuality());
            }
            assertEquals(0, executor.getQueue().size(), "a local ticket must not consume remote resolver queue space");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test void localTicketsStillRespectDisabledPrimaryAndExhaustedCallerBudget() {
        when(config.getValue(MusicPlaybackService.CFG_PLAY_RESOLVER_ORDER))
                .thenReturn(Optional.of("cross_source"));
        MusicService service = service(task -> { throw new RejectedExecutionException("full"); }, new MusicCache());
        assertEquals(MusicErrorCode.NO_PLAYABLE_URL,
                assertThrows(MusicBusinessException.class, () -> service.play("qs", "disabled", null)).getErrorCode());
        when(config.getValue(MusicPlaybackService.CFG_PLAY_RESOLVER_ORDER)).thenReturn(Optional.empty());
        try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(0, TimeUnit.NANOSECONDS)) {
            assertEquals(MusicErrorCode.UPSTREAM_TIMEOUT,
                    assertThrows(MusicBusinessException.class, () -> service.play("qs", "expired", null)).getErrorCode());
        }
    }
}
