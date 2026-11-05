package com.example.website.service.music;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.website.config.PerformanceMetrics;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * In-memory TTL cache for music endpoints. Keys are opaque strings built by
 * callers (e.g. "play:qq:12345:flac"); values are whatever the caller wants.
 *
 * <p>Entries carry their own TTL while Caffeine bounds the number of retained
 * keys. Expired entries are also removed lazily when read.
 */
@Component
public class MusicCache {

    private static final int MAX_ENTRIES = 10_000;

    private final Cache<String, Entry> store = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterAccess(24, TimeUnit.HOURS)
            .build();
    private final PerformanceMetrics metrics;
    private final LongSupplier clock;

    public MusicCache() {
        this(new PerformanceMetrics(), System::currentTimeMillis);
    }

    @Autowired
    public MusicCache(PerformanceMetrics metrics) {
        this(metrics, System::currentTimeMillis);
    }

    MusicCache(PerformanceMetrics metrics, LongSupplier clock) {
        this.metrics = metrics;
        this.clock = clock;
    }

    public <T> T get(String key, Class<T> type) {
        Entry e = store.getIfPresent(key);
        if (e == null) { metrics.recordCacheMiss("music"); return null; }
        if (e.expiresAtMs < clock.getAsLong()) {
            store.asMap().remove(key, e);
            metrics.recordCacheMiss("music");
            return null;
        }
        Object v = e.value;
        if (!type.isInstance(v)) { metrics.recordCacheMiss("music"); return null; }
        metrics.recordCacheHit("music");
        return type.cast(v);
    }

    public void put(String key, Object value, long ttlSeconds) {
        if (value == null || ttlSeconds <= 0) return;
        store.put(key, new Entry(value, clock.getAsLong() + ttlSeconds * 1000L));
    }

    public void invalidate(String key) {
        store.invalidate(key);
    }

    public int size() {
        return Math.toIntExact(store.estimatedSize());
    }

    private static final class Entry {
        final Object value;
        final long expiresAtMs;

        Entry(Object value, long expiresAtMs) {
            this.value = value;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
