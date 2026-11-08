package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.MusicSource;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Limits unique uncached resolutions independently for each requested source. */
final class MusicLoadGuard {
    private final Map<MusicSource, Semaphore> permits = new EnumMap<>(MusicSource.class);

    MusicLoadGuard(int perSource) {
        if (perSource < 1) throw new IllegalArgumentException("perSource must be positive");
        for (MusicSource source : MusicSource.values()) permits.put(source, new Semaphore(perSource));
    }

    Lease acquire(MusicSource source) {
        Semaphore semaphore = permits.get(source);
        if (!semaphore.tryAcquire()) throw new MusicBusinessException(MusicErrorCode.SERVICE_BUSY);
        return new Lease(semaphore);
    }

    static final class Lease implements AutoCloseable {
        private final Semaphore semaphore;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(Semaphore semaphore) { this.semaphore = semaphore; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) semaphore.release();
        }
    }
}
