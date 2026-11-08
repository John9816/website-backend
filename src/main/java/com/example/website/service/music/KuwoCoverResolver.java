package com.example.website.service.music;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.Metrics;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Optional chart artwork: bounded retention, shared lookups and a deadline that includes queue time. */
final class KuwoCoverResolver implements AutoCloseable {

    private static final String COVER_RESOLVE_URL = "http://artistpicserver.kuwo.cn/pic.web";
    private static final int MAX_COVER_RESPONSE_BYTES = 8 * 1024;
    private final Call.Factory calls;
    private final MusicMetrics metrics;
    private final Cache<String, String> completed;
    private final Map<String, Lookup> inFlight = new HashMap<>();
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor deadlines;
    private final int maxOutstanding;
    private final long timeoutNanos;
    private boolean closed;

    KuwoCoverResolver(Call.Factory calls) {
        this(calls, new MusicMetrics(Metrics.globalRegistry));
    }

    KuwoCoverResolver(Call.Factory calls, MusicMetrics metrics) {
        this(calls, 10_000, Duration.ofHours(6), 8, 56, Duration.ofSeconds(5), Ticker.systemTicker(), metrics);
    }

    KuwoCoverResolver(Call.Factory calls, long maximumEntries, Duration retention,
                      int concurrency, int queueCapacity, Duration timeout, Ticker ticker) {
        this(calls, maximumEntries, retention, concurrency, queueCapacity, timeout, ticker,
                new MusicMetrics(Metrics.globalRegistry));
    }

    KuwoCoverResolver(Call.Factory calls, long maximumEntries, Duration retention,
                      int concurrency, int queueCapacity, Duration timeout, Ticker ticker, MusicMetrics metrics) {
        this.calls = calls;
        this.metrics = metrics;
        this.maxOutstanding = concurrency + queueCapacity;
        this.timeoutNanos = timeout.toNanos();
        this.completed = Caffeine.newBuilder().maximumSize(maximumEntries)
                .expireAfterWrite(retention.toNanos(), TimeUnit.NANOSECONDS)
                .ticker(ticker).executor(Runnable::run).build();
        this.workers = new ThreadPoolExecutor(concurrency, concurrency, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), daemonThreads("kuwo-cover"),
                new ThreadPoolExecutor.AbortPolicy());
        this.workers.allowCoreThreadTimeOut(true);
        this.deadlines = new ScheduledThreadPoolExecutor(1, daemonThreads("kuwo-cover-deadline"));
        this.deadlines.setRemoveOnCancelPolicy(true);
    }

    synchronized CompletableFuture<String> resolve(String songId) {
        if (closed || songId == null || songId.isEmpty()) return CompletableFuture.completedFuture(null);
        long startedNanos = System.nanoTime();
        String cached = completed.getIfPresent(songId);
        metrics.cache("kuwo", "cover", cached != null);
        if (cached != null) {
            metrics.record("kuwo", "cover", "cache_hit", startedNanos);
            return CompletableFuture.completedFuture(cached);
        }
        Lookup existing = inFlight.get(songId);
        if (existing != null) {
            metrics.record("kuwo", "cover", "duplicate", startedNanos);
            return existing.result;
        }
        if (inFlight.size() >= maxOutstanding) {
            metrics.rejected("kuwo", "cover");
            metrics.record("kuwo", "cover", "rejected", startedNanos);
            return CompletableFuture.completedFuture(null);
        }

        Lookup lookup = new Lookup(songId, startedNanos, timeoutNanos);
        inFlight.put(songId, lookup);
        try {
            HttpUrl url = HttpUrl.parse(COVER_RESOLVE_URL).newBuilder()
                    .addQueryParameter("type", "rid_pic").addQueryParameter("pictype", "url")
                    .addQueryParameter("size", "500").addQueryParameter("rid", songId).build();
            lookup.call = calls.newCall(new Request.Builder().url(url)
                    .header("Referer", "http://www.kuwo.cn/").header("User-Agent", "Mozilla/5.0").get().build());
            lookup.call.timeout().timeout(timeoutNanos, TimeUnit.NANOSECONDS).deadlineNanoTime(lookup.deadlineNanos);
            lookup.task = new FutureTask<>(() -> {
                load(lookup);
                return null;
            });
            lookup.deadline = deadlines.schedule(() -> finish(lookup, null, "timeout", true),
                    Math.max(0, lookup.deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            workers.execute(lookup.task);
        } catch (RuntimeException ex) {
            finish(lookup, null, ex instanceof RejectedExecutionException ? "rejected" : "failure", true);
        }
        return lookup.result;
    }

    private void load(Lookup lookup) {
        String cover = null;
        try (Response response = lookup.call.execute()) {
            ResponseBody body = response.body();
            if (response.isSuccessful() && body != null) {
                String candidate = readCoverUrl(body);
                if (isValidKuwoImageUrl(candidate)) cover = candidate;
            }
        } catch (Exception ignored) {
            // Artwork is optional; a later page may retry transient failures.
        } finally {
            finish(lookup, cover, cover == null ? "failure" : "success", false);
        }
    }

    private static String readCoverUrl(ResponseBody body) throws IOException {
        byte[] bytes = new byte[MAX_COVER_RESPONSE_BYTES + 1];
        InputStream input = body.byteStream();
        int length = 0;
        while (length < bytes.length) {
            int read = input.read(bytes, length, bytes.length - length);
            if (read < 0) break;
            length += read;
        }
        if (length > MAX_COVER_RESPONSE_BYTES) return null;
        return new String(bytes, 0, length, StandardCharsets.UTF_8).trim();
    }

    private void finish(Lookup lookup, String cover, String outcome, boolean cancel) {
        synchronized (this) {
            if (!lookup.terminal.compareAndSet(false, true)) return;
            if (!cancel && System.nanoTime() - lookup.deadlineNanos >= 0) {
                cover = null;
                outcome = "timeout";
            }
            if (cover != null) completed.put(lookup.songId, cover);
            inFlight.remove(lookup.songId, lookup);
        }
        if (lookup.deadline != null) lookup.deadline.cancel(false);
        if (cancel) {
            if (lookup.call != null) lookup.call.cancel();
            if (lookup.task != null) {
                lookup.task.cancel(true);
                // Canceled queued tasks must not occupy admission after their deadline.
                workers.remove(lookup.task);
            }
        }
        if ("rejected".equals(outcome)) metrics.rejected("kuwo", "cover");
        metrics.record("kuwo", "cover", outcome, lookup.startedNanos);
        lookup.result.complete(cover);
    }

    @Override
    public void close() {
        List<Lookup> pending;
        synchronized (this) {
            closed = true;
            pending = new ArrayList<>(inFlight.values());
        }
        for (Lookup lookup : pending) finish(lookup, null, "cancelled", true);
        workers.shutdownNow();
        deadlines.shutdownNow();
    }

    private static boolean isValidKuwoImageUrl(String candidate) {
        if (candidate == null || candidate.isEmpty()) return false;
        HttpUrl url = HttpUrl.parse(candidate);
        if (url == null) return false;
        String host = url.host().toLowerCase(Locale.ROOT);
        return (host.equals("kuwo.cn") || host.endsWith(".kuwo.cn"))
                && !url.encodedPath().isEmpty() && !url.encodedPath().endsWith("/");
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class Lookup {
        private final String songId;
        private final long startedNanos;
        private final long deadlineNanos;
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private Call call;
        private FutureTask<Void> task;
        private ScheduledFuture<?> deadline;

        private Lookup(String songId, long startedNanos, long timeoutNanos) {
            this.songId = songId;
            this.startedNanos = startedNanos;
            this.deadlineNanos = startedNanos + timeoutNanos;
        }
    }
}
