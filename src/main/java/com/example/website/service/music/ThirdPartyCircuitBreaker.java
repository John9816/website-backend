package com.example.website.service.music;

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
    private boolean halfOpenProbe;

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

    synchronized void acquire(MusicErrorCode errorCode) {
        long now = clock.getAsLong();
        if (openUntilMillis == 0L) return;
        if (now < openUntilMillis || halfOpenProbe) {
            throw new MusicBusinessException(errorCode,
                    upstream + " is temporarily unavailable (circuit open)");
        }
        halfOpenProbe = true;
    }

    synchronized void recordSuccess() {
        consecutiveFailures = 0;
        openUntilMillis = 0L;
        halfOpenProbe = false;
    }

    synchronized void recordFailure() {
        halfOpenProbe = false;
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            openUntilMillis = clock.getAsLong() + openMillis;
        }
    }
}
