package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import com.example.website.common.MusicBusinessException;
import com.example.website.module.music.api.dto.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Constructor;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QishuiRoamingServiceTests {
    private final QishuiFeedClient feed = mock(QishuiFeedClient.class);
    private final QishuiCredentialStore store = mock(QishuiCredentialStore.class);
    private final Instant now = Instant.parse("2026-11-09T01:00:00Z");
    private final QishuiRoamingService service = new QishuiRoamingService(feed, store, Clock.fixed(now, ZoneOffset.UTC));

    private QishuiCredentialStore.StoredCredential bind(long user, String generation) throws Exception {
        QishuiCredential c = new QishuiCredential();
        c.setUserId("upstream-" + user); c.setDeviceId("device-" + user); c.setExpiresAt(now.plusSeconds(3600));
        Constructor<QishuiCredentialStore.StoredCredential> ctor = QishuiCredentialStore.StoredCredential.class
                .getDeclaredConstructor(String.class, QishuiCredential.class);
        ctor.setAccessible(true);
        QishuiCredentialStore.StoredCredential value = ctor.newInstance(generation, c);
        when(store.load(user)).thenReturn(Optional.of(value));
        when(store.isCurrent(eq(user),anyString())).thenAnswer(inv -> generation.equals(inv.getArgument(1)));
        return value;
    }
    private QishuiFeedClient.Page page(boolean more, String... ids) {
        List<SongSearchItem> songs = new ArrayList<>();
        for (String id : ids) { SongSearchItem s = new SongSearchItem(); s.setId(id); songs.add(s); }
        return new QishuiFeedClient.Page(songs, more);
    }

    @Test void paginationIsIdempotentAndDeduplicatesAcrossPages() throws Exception {
        QishuiCredential c = bind(7, "b1").getCredential();
        when(feed.songs(eq(c), eq("recommended"), anyLong(), anyLong(), isNull()))
                .thenReturn(page(true,"a","b"), page(true,"b","c"));
        QishuiRoamingPageView first = service.start(7L, null);
        QishuiRoamingPageView next = service.next(7L, first.getSessionId(), 1, null);
        assertEquals(0, first.getSequence()); assertEquals(1, first.getNextSequence());
        assertEquals(1, next.getList().size()); assertEquals("c", next.getList().get(0).getId());
        assertEquals(next, service.next(7L, first.getSessionId(), 1, null));
        verify(feed, times(2)).songs(eq(c), eq("recommended"), anyLong(), anyLong(), isNull());
        assertThrows(BusinessException.class, () -> service.next(7L, first.getSessionId(), 3, null));
    }

    @Test void sessionOwnershipAndBindingGenerationAreCheckedOnReplay() throws Exception {
        QishuiCredential c = bind(7, "b1").getCredential(); bind(8, "b8");
        when(feed.songs(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(page(true,"a"));
        QishuiRoamingPageView first = service.start(7L, null);
        assertThrows(BusinessException.class, () -> service.next(8L, first.getSessionId(), 1, null));
        bind(7, "b2");
        assertThrows(BusinessException.class, () -> service.next(7L, first.getSessionId(), 1, null));
        verify(feed, times(1)).songs(eq(c), eq("recommended"), anyLong(), anyLong(), isNull());
    }

    @Test void bindingChangedDuringRequestDiscardsTheResponse() throws Exception {
        bind(7, "b1");
        when(feed.songs(any(), anyString(), anyLong(), anyLong(), any())).thenAnswer(inv -> {
            when(store.load(7L)).thenReturn(Optional.empty());
            when(store.isCurrent(eq(7L),anyString())).thenReturn(false);
            return page(true,"private");
        });
        assertThrows(BusinessException.class, () -> service.start(7L, null));
    }

    @Test void bindingChangedWhileLoadingModesPreventsSendingPersonalFeed() throws Exception {
        bind(7,"b1");
        when(feed.modes(any())).thenAnswer(inv -> {
            bind(7,"b2");
            return Collections.singletonList(new QishuiFeedModeView("2","运动","场景",null,null));
        });
        when(feed.songs(any(),anyString(),anyLong(),anyLong(),any())).thenReturn(page(true,"a"));
        assertThrows(BusinessException.class, () -> service.start(7L,"2"));
        verify(feed,never()).songs(any(),anyString(),anyLong(),anyLong(),any());
    }

    @Test void unboundAndExpiredAccountsCannotSendFeedRequests() throws Exception {
        when(store.load(7L)).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.start(7L, null));
        bind(7,"b1").getCredential().setExpiresAt(now);
        assertThrows(BusinessException.class, () -> service.modes(7L));
        verifyNoInteractions(feed);
    }

    @Test void switchingModeResetsModeCounterAndUsesServerSelectionTime() throws Exception {
        QishuiCredential c = bind(7,"b1").getCredential();
        when(feed.modes(c)).thenReturn(Arrays.asList(new QishuiFeedModeView("2","运动","场景",null,null)));
        when(feed.songs(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(page(false,"a"),page(true,"b"));
        QishuiRoamingPageView first = service.start(7L, null);
        QishuiRoamingPageView next = service.next(7L, first.getSessionId(), 1, "2");
        assertEquals("2",next.getModeId());
        verify(feed).songs(c,"2",2,1,now.getEpochSecond());
        assertThrows(BusinessException.class, () -> service.next(7L,first.getSessionId(),1,"recommended"));
        assertThrows(BusinessException.class, () -> service.next(7L,first.getSessionId(),2,"999"));
    }

    @Test void emptyAndDuplicateBatchesHaveABoundedRetryBudget() throws Exception {
        bind(7,"b1");
        when(feed.songs(any(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(page(true,"a"),page(true,"a"),page(true,"a"),page(true,"a"));
        QishuiRoamingPageView first = service.start(7L, null);
        assertThrows(MusicBusinessException.class, () -> service.next(7L,first.getSessionId(),1,null));
        verify(feed,times(4)).songs(any(), anyString(), anyLong(), anyLong(), any());
    }

    @Test void closedSessionsCannotBeContinued() throws Exception {
        bind(7,"b1"); when(feed.songs(any(),anyString(),anyLong(),anyLong(),any())).thenReturn(page(true,"a"));
        QishuiRoamingPageView first = service.start(7L,null);
        service.close(7L,first.getSessionId());
        assertThrows(BusinessException.class, () -> service.next(7L,first.getSessionId(),1,null));
    }

    @Test void browsingModesThenStartingRoamingReusesOnlyTheSameBindingModes() throws Exception {
        QishuiCredential first = bind(7,"b1").getCredential();
        when(feed.modes(first)).thenReturn(Collections.singletonList(new QishuiFeedModeView("2","运动","场景",null,null)));
        when(feed.songs(any(),anyString(),anyLong(),anyLong(),any())).thenReturn(page(true,"a"));
        service.modes(7L).get(0).setName("changed by caller");
        assertEquals("运动",service.modes(7L).get(0).getName());
        service.start(7L,"2");
        verify(feed,times(1)).modes(first);
        QishuiCredential rebound = bind(7,"b2").getCredential();
        when(feed.modes(rebound)).thenReturn(Collections.singletonList(new QishuiFeedModeView("4","深夜","场景",null,null)));
        assertEquals("4",service.modes(7L).get(0).getId());
        verify(feed).modes(rebound);
    }

    @Test void modeCacheExpiresAndCannotHideRevokedBindings() throws Exception {
        QishuiCredential c = bind(7,"b1").getCredential();
        java.util.concurrent.atomic.AtomicLong tick = new java.util.concurrent.atomic.AtomicLong();
        QishuiRoamingService timed = new QishuiRoamingService(feed,store,Clock.fixed(now,ZoneOffset.UTC),tick::get);
        when(feed.modes(c)).thenReturn(
                Collections.singletonList(new QishuiFeedModeView("2","运动","场景",null,null)),
                Collections.singletonList(new QishuiFeedModeView("4","深夜","场景",null,null)));
        assertEquals("2",timed.modes(7L).get(0).getId());
        tick.set(TimeUnit.SECONDS.toNanos(299));
        assertEquals("2",timed.modes(7L).get(0).getId());
        tick.set(TimeUnit.SECONDS.toNanos(301));
        assertEquals("4",timed.modes(7L).get(0).getId());
        verify(feed,times(2)).modes(c);
        when(store.isCurrent(7L,"b1")).thenReturn(false);
        assertEquals(409,assertThrows(BusinessException.class,() -> timed.modes(7L)).getCode());
    }

    @Test void eachRoamingPageDecryptsOnceButReplayStillChecksLiveBinding() throws Exception {
        bind(7,"b1");
        when(feed.songs(any(),anyString(),anyLong(),anyLong(),any())).thenReturn(page(true,"a"),page(true,"b"));
        QishuiRoamingPageView first = service.start(7L,null);
        clearInvocations(store);
        assertEquals("b",service.next(7L,first.getSessionId(),1,null).getList().get(0).getId());
        verify(store,times(1)).load(7L);
        verify(store,atLeastOnce()).isCurrent(7L,"b1");
        // Simulate a stale ORM snapshot while the lightweight database check sees revocation.
        when(store.isCurrent(7L,"b1")).thenReturn(false);
        assertEquals(409,assertThrows(BusinessException.class,
                () -> service.next(7L,first.getSessionId(),1,null)).getCode());
    }

    @Test void failedModeSwitchDoesNotChangeTheCurrentModeOrConsumeSequence() throws Exception {
        QishuiCredential c = bind(7,"b1").getCredential();
        when(feed.modes(c)).thenReturn(Collections.singletonList(new QishuiFeedModeView("2","运动","场景",null,null)));
        when(feed.songs(eq(c),eq("recommended"),anyLong(),anyLong(),isNull()))
                .thenReturn(page(true,"a"), page(true,"b"));
        when(feed.songs(eq(c),eq("2"),anyLong(),anyLong(),any()))
                .thenThrow(QishuiHttpClient.failure("test_failure"));
        QishuiRoamingPageView first = service.start(7L,null);
        assertThrows(MusicBusinessException.class, () -> service.next(7L,first.getSessionId(),1,"2"));
        QishuiRoamingPageView retried = service.next(7L,first.getSessionId(),1,null);
        assertEquals("recommended",retried.getModeId());
        assertEquals("b",retried.getList().get(0).getId());
        verify(feed).songs(c,"recommended",2,2,null);
    }

    @Test void replayCannotBeMutatedByAConsumer() throws Exception {
        bind(7,"b1");
        when(feed.songs(any(),anyString(),anyLong(),anyLong(),any())).thenReturn(page(true,"a"),page(false,"b"));
        QishuiRoamingPageView first = service.start(7L,null);
        QishuiRoamingPageView next = service.next(7L,first.getSessionId(),1,null);
        next.getList().get(0).setId("modified");
        next.setModeId("2"); next.setNextSequence(999);
        QishuiRoamingPageView replay = service.next(7L,first.getSessionId(),1,null);
        assertEquals("b",replay.getList().get(0).getId());
        assertEquals("recommended",replay.getModeId()); assertEquals(2,replay.getNextSequence());
    }

    @Test void endedFeedDoesNotFetchAgainAndAuthorizationIsCheckedOnReplay() throws Exception {
        QishuiCredential c = bind(7,"b1").getCredential();
        when(feed.songs(any(),anyString(),anyLong(),anyLong(),any())).thenReturn(page(false,"a"));
        QishuiRoamingPageView first = service.start(7L,null);
        QishuiRoamingPageView end = service.next(7L,first.getSessionId(),1,null);
        assertFalse(end.isHasMore()); assertTrue(end.getList().isEmpty());
        c.setExpiresAt(now);
        assertEquals(409,assertThrows(BusinessException.class,
                () -> service.next(7L,first.getSessionId(),1,null)).getCode());
        verify(feed,times(1)).songs(any(),anyString(),anyLong(),anyLong(),any());
    }

    @Test void absoluteExpiryIsEnforcedEvenWhenItPassesDuringHttp() throws Exception {
        bind(7,"b1");
        AtomicReference<Instant> time = new AtomicReference<>(now);
        Clock clock = mock(Clock.class); when(clock.instant()).thenAnswer(inv -> time.get());
        when(clock.millis()).thenAnswer(inv -> time.get().toEpochMilli());
        QishuiRoamingService timed = new QishuiRoamingService(feed,store,clock);
        when(feed.songs(any(),anyString(),anyLong(),anyLong(),any())).thenReturn(page(true,"a"));
        QishuiRoamingPageView first = timed.start(7L,null);
        time.set(now.plusSeconds(1799));
        when(feed.songs(any(),anyString(),anyLong(),anyLong(),any())).thenAnswer(inv -> {
            time.set(now.plusSeconds(1801)); return page(true,"late");
        });
        assertEquals(404,assertThrows(BusinessException.class,
                () -> timed.next(7L,first.getSessionId(),1,null)).getCode());
        assertEquals(404,assertThrows(BusinessException.class,
                () -> timed.next(7L,first.getSessionId(),1,null)).getCode());
    }

    @Test void overlappingRequestsFailPromptlyWithoutDuplicatingUpstreamWork() throws Exception {
        bind(7,"b1");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(feed.songs(any(),anyString(),anyLong(),anyLong(),any())).thenAnswer(inv -> {
            entered.countDown(); assertTrue(release.await(3,TimeUnit.SECONDS)); return page(true,"a");
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<QishuiRoamingPageView> first = executor.submit(() -> service.start(7L,null));
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> assertThrows(MusicBusinessException.class, () -> service.start(7L,null)));
            verify(feed,times(1)).songs(any(),anyString(),anyLong(),anyLong(),any());
        } finally { release.countDown(); executor.shutdown(); }
        assertEquals(1,first.get(3,TimeUnit.SECONDS).getList().size());
    }
}
