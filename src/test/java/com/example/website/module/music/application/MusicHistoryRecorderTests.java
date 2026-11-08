package com.example.website.module.music.application;

import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.PlayInfo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MusicHistoryRecorderTests {
    @Test
    void gracefulShutdownDrainsNewerSnapshotAcceptedDuringActiveWrite() throws Exception {
        MusicLibraryService library = mock(MusicLibraryService.class);
        MusicHistoryRecorder recorder = new MusicHistoryRecorder(library, new MusicMetrics(new SimpleMeterRegistry()));
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        List<String> titles = Collections.synchronizedList(new ArrayList<>());
        doAnswer(call -> {
            PlayInfo info = call.getArgument(1);
            if (info.getName().equals("old")) {
                started.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
            }
            titles.add(info.getName());
            return null;
        }).when(library).recordPlay(eq(1L), any(), any());
        Thread closer = new Thread(recorder::close);
        try {
            recorder.submit(1L, song("song", "old"));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            recorder.submit(1L, song("song", "latest"));
            closer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (closer.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(Thread.State.TIMED_WAITING, closer.getState());
            release.countDown();
            closer.join(2000);
            assertFalse(closer.isAlive());
            assertEquals(java.util.Arrays.asList("old", "latest"), titles);
        } finally {
            release.countDown();
            closer.join(6000);
            recorder.close();
        }
    }

    @Test
    void slowWriterKeepsQueueBoundedAndCoalescesLatestSnapshot() throws Exception {
        MusicLibraryService library = mock(MusicLibraryService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MusicHistoryRecorder recorder = new MusicHistoryRecorder(library, new MusicMetrics(registry), 2);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), saved = new CountDownLatch(2);
        List<String> titles = Collections.synchronizedList(new ArrayList<>());
        doAnswer(call -> {
            PlayInfo info = call.getArgument(1);
            if (info.getId().equals("first")) { started.countDown(); release.await(3, TimeUnit.SECONDS); }
            titles.add(info.getName()); saved.countDown(); return null;
        }).when(library).recordPlay(eq(1L), any(), any());
        try {
            recorder.submit(1L, song("first", "first"));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            recorder.submit(1L, song("second", "old"));
            PlayInfo latest = song("second", "latest");
            recorder.submit(1L, latest);
            latest.setName("mutated after submit");
            recorder.submit(1L, song("third", "must reject"));
            assertEquals(1, registry.get("music.rejections").tag("operation", "history").counter().count());
            release.countDown();
            assertTrue(saved.await(2, TimeUnit.SECONDS));
            assertEquals(java.util.Arrays.asList("first", "latest"), titles);
        } finally { release.countDown(); recorder.close(); }
    }

    @Test
    void transientStorageFailureRetriesSameEventWithoutBlockingSubmit() throws Exception {
        MusicLibraryService library = mock(MusicLibraryService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MusicHistoryRecorder recorder = new MusicHistoryRecorder(library, new MusicMetrics(registry));
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch saved = new CountDownLatch(1);
        List<Object> eventTimes = Collections.synchronizedList(new ArrayList<>());
        doAnswer(call -> {
            eventTimes.add(call.getArgument(2));
            if (attempts.incrementAndGet() < 3) throw new IllegalStateException("temporary");
            saved.countDown(); return null;
        }).when(library).recordPlay(eq(1L), any(), any());
        try {
            recorder.submit(1L, song("song", "song"));
            assertTrue(saved.await(3, TimeUnit.SECONDS));
        } finally { recorder.close(); }
        assertEquals(3, attempts.get());
        assertEquals(1, eventTimes.stream().distinct().count());
        assertEquals(2, registry.get("music.requests").tag("operation", "history").tag("outcome", "retry").counter().count());
    }

    private static PlayInfo song(String id, String title) {
        PlayInfo info = new PlayInfo(); info.setId(id); info.setName(title); info.setSource(MusicSource.QQ); return info;
    }
}
