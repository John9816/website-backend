package com.example.website.module.music.application;

import com.example.website.module.music.api.dto.PlayInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Best-effort history writes, isolated from the playback response with bounded retries. */
@Slf4j
@Component
public class MusicHistoryRecorder {
    private final MusicLibraryService library;
    private final MusicMetrics metrics;
    private final ScheduledThreadPoolExecutor worker;
    private final int capacity;
    private final Map<String, Pending> pending = new HashMap<>();
    private boolean closed;

    @Autowired
    public MusicHistoryRecorder(MusicLibraryService library, MusicMetrics metrics) {
        this(library, metrics, 256);
    }

    MusicHistoryRecorder(MusicLibraryService library, MusicMetrics metrics, int capacity) {
        this.library = library;
        this.metrics = metrics;
        this.capacity = capacity;
        // One writer preserves per-user retention ordering without parallel trim races.
        this.worker = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "music-history");
            thread.setDaemon(true);
            return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
    }

    public void submit(Long userId, PlayInfo info) {
        if (userId == null || info == null || info.getSource() == null
                || info.getId() == null || info.getId().trim().isEmpty()) return;
        String source = info.getSource().getValue();
        String key = userId + ":" + source + ":" + info.getId();
        synchronized (pending) {
            Pending existing = pending.get(key);
            if (closed || (existing == null && pending.size() >= capacity)) {
                metrics.rejected(source, "history");
                return;
            }
            if (existing != null) {
                existing.info = snapshot(info);
                existing.playedAt = LocalDateTime.now();
                existing.version++;
                metrics.record(source, "history", "duplicate", System.nanoTime());
                return;
            }
            Pending item = new Pending(userId, snapshot(info));
            pending.put(key, item);
            schedule(key, item, 0);
        }
    }

    private void schedule(String key, Pending item, long delayMillis) {
        try {
            worker.schedule(() -> write(key, item), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            pending.remove(key, item);
            pending.notifyAll();
            metrics.rejected(item.info.getSource().getValue(), "history");
        }
    }

    private void write(String key, Pending item) {
        PlayInfo info;
        LocalDateTime playedAt;
        long version;
        synchronized (pending) {
            info = item.info;
            playedAt = item.playedAt;
            version = item.version;
        }
        long started = System.nanoTime();
        boolean success = false;
        try {
            library.recordPlay(item.userId, info, playedAt);
            success = true;
        } catch (RuntimeException ex) {
            // Avoid song/user identifiers and database exception messages in metrics/logs.
            log.warn("Music history write failed: {}", ex.getClass().getSimpleName());
        }
        synchronized (pending) {
            String source = info.getSource().getValue();
            metrics.record(source, "history", success ? "success" : "failure", started);
            if (item.version != version) {
                item.attempts = 0;
                schedule(key, item, 0);
            } else if (!success && ++item.attempts < 3 && !closed) {
                metrics.record(source, "history", "retry", System.nanoTime());
                schedule(key, item, item.attempts == 1 ? 250 : 1000);
            } else {
                pending.remove(key, item);
                pending.notifyAll();
                if (!success) metrics.record(source, "history", "discarded", System.nanoTime());
            }
        }
    }

    private static PlayInfo snapshot(PlayInfo info) {
        PlayInfo copy = new PlayInfo();
        copy.setId(info.getId()); copy.setSource(info.getSource());
        copy.setName(info.getName()); copy.setArtist(info.getArtist()); copy.setAlbum(info.getAlbum());
        copy.setCoverUrl(info.getCoverUrl()); copy.setDurationSec(info.getDurationSec());
        return copy;
    }

    @PreDestroy
    public void close() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            synchronized (pending) {
                closed = true;
                // Keep scheduling available while accepted, coalesced updates drain.
                while (!pending.isEmpty()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    TimeUnit.NANOSECONDS.timedWait(pending, remaining);
                }
            }
            worker.shutdown();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !worker.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException ex) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class Pending {
        final Long userId;
        PlayInfo info;
        LocalDateTime playedAt = LocalDateTime.now();
        long version;
        int attempts;
        Pending(Long userId, PlayInfo info) { this.userId = userId; this.info = info; }
    }
}
