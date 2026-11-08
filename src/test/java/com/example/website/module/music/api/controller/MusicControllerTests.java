package com.example.website.module.music.api.controller;

import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.PlayInfo;
import com.example.website.module.membership.application.MembershipService;
import com.example.website.module.music.application.MusicLibraryService;
import com.example.website.module.music.application.MusicHistoryRecorder;
import com.example.website.module.music.application.MusicMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.example.website.module.music.application.MusicService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MusicControllerTests {

    @Test
    void playbackResponseDoesNotWaitForSlowHistoryStorage() throws Exception {
        MusicService service = mock(MusicService.class);
        MusicLibraryService library = mock(MusicLibraryService.class);
        MembershipService membership = mock(MembershipService.class);
        MusicHistoryRecorder recorder = new MusicHistoryRecorder(library, new MusicMetrics(new SimpleMeterRegistry()));
        MusicController controller = new MusicController(service, recorder, membership);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 42L);
        PlayInfo info = new PlayInfo(); info.setId("song"); info.setSource(MusicSource.QQ);
        when(service.play("qq", "song", "320k")).thenReturn(info);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            release.await(3, java.util.concurrent.TimeUnit.SECONDS); return null;
        }).when(library).recordPlay(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        java.util.concurrent.ExecutorService requestThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> response = requestThread.submit(
                    () -> controller.play(request, "qq", "song", "320k", false));
            org.junit.jupiter.api.Assertions.assertNotNull(response.get(300, java.util.concurrent.TimeUnit.MILLISECONDS));
        } finally { release.countDown(); requestThread.shutdownNow(); recorder.close(); }
    }

    @Test
    void prefetchStillChecksMembershipButDoesNotWriteHistory() {
        MusicService musicService = mock(MusicService.class);
        MusicHistoryRecorder libraryService = mock(MusicHistoryRecorder.class);
        MembershipService membershipService = mock(MembershipService.class);
        MusicController controller = new MusicController(musicService, libraryService, membershipService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 42L);
        PlayInfo info = new PlayInfo();
        info.setId("song-id");
        info.setSource(MusicSource.QQ);
        when(musicService.play("qq", "song-id", "320k")).thenReturn(info);

        controller.play(request, "qq", "song-id", "320k", true);

        verify(membershipService).requireActiveMembership(42L);
        verify(libraryService, never()).submit(42L, info);

        controller.play(request, "qq", "song-id", "320k", false);

        verify(membershipService, times(2)).requireActiveMembership(42L);
        verify(libraryService).submit(42L, info);
    }
}
