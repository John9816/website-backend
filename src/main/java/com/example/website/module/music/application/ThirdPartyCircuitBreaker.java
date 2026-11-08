package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Small per-upstream circuit breaker so optional providers fail fast during outages. */
final class ThirdPartyCircuitBreaker {

    private static final int DEFAULT_FAILURE_THRESHOLD = 3;
    private static final long DEFAULT_OPEN_MILLIS = TimeUnit.SECONDS.toMillis(60);

    private final String upstream;
    private final int failureThreshold;
    private final long openMillis;
    private final LongSupplier clock;

    private int consecutiveFailures;
    private long openUntilMillis;
    private Attempt halfOpenProbe;

    ThirdPartyCircuitBreaker(String upstream) {
        this(upstream, DEFAULT_FAILURE_THRESHOLD, DEFAULT_OPEN_MILLIS, System::currentTimeMillis);
    }

    ThirdPartyCircuitBreaker(String upstream,
                             int failureThreshold,
                             long openMillis,
                             LongSupplier clock) {
        this.upstream = upstream;
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
        this.clock = clock;
    }

    synchronized Attempt acquire(MusicErrorCode errorCode) {
        long now = clock.getAsLong();
        if (openUntilMillis == 0L) return Attempt.UNRESTRICTED;
        if (now < openUntilMillis || halfOpenProbe != null) {
            throw new MusicBusinessException(errorCode,
                    upstream + " is temporarily unavailable (circuit open)");
        }
        halfOpenProbe = new Attempt(this);
        return halfOpenProbe;
    }

    synchronized void recordSuccess() {
        consecutiveFailures = 0;
        openUntilMillis = 0L;
        halfOpenProbe = null;
    }

    synchronized void recordFailure() {
        halfOpenProbe = null;
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            openUntilMillis = clock.getAsLong() + openMillis;
        }
    }

    /** Closing an abandoned probe neither erases failures nor starts a new cooldown. */
    static final class Attempt implements AutoCloseable {
        private static final Attempt UNRESTRICTED = new Attempt(null);
        private final ThirdPartyCircuitBreaker owner;

        private Attempt(ThirdPartyCircuitBreaker owner) { this.owner = owner; }

        @Override public void close() {
            if (owner == null) return;
            synchronized (owner) {
                if (owner.halfOpenProbe == this) owner.halfOpenProbe = null;
            }
        }
    }
}
