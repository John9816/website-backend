package com.example.website.service.music;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory TTL cache for music endpoints. Keys are opaque strings built by
 * callers (e.g. "play:qq:12345:flac"); values are whatever the caller wants.
 *
 * <p>No eviction thread — entries are only removed on read-after-expiry.
 * Acceptable at this project's traffic level; swap to Caffeine later if the
 * map grows unbounded in practice.
 */
@Component
public class MusicCache {

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public <T> T get(String key, Class<T> type) {
        Entry e = store.get(key);
        if (e == null) return null;
        if (e.expiresAtMs < System.currentTimeMillis()) {
            store.remove(key, e);
            return null;
        }
        Object v = e.value;
        return type.isInstance(v) ? type.cast(v) : null;
    }

    public void put(String key, Object value, long ttlSeconds) {
        if (value == null || ttlSeconds <= 0) return;
        store.put(key, new Entry(value, System.currentTimeMillis() + ttlSeconds * 1000L));
    }

    public void invalidate(String key) {
        store.remove(key);
    }

    public int size() {
        return store.size();
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
