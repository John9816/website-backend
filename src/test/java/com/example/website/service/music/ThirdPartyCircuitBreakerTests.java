package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThirdPartyCircuitBreakerTests {

    @Test
    void closingOtherOrPreviousAttemptsCannotReleaseTheCurrentHalfOpenProbe() {
        AtomicLong clock = new AtomicLong(1_000L);
        ThirdPartyCircuitBreaker breaker = new ThirdPartyCircuitBreaker("provider", 3, 60_000L, clock::get);
        ThirdPartyCircuitBreaker.Attempt unrestricted = breaker.acquire(MusicErrorCode.UPSTREAM_PLAY_FAILED);
        for (int i = 0; i < 3; i++) breaker.recordFailure();
        clock.addAndGet(60_000L);

        ThirdPartyCircuitBreaker.Attempt first = breaker.acquire(MusicErrorCode.UPSTREAM_PLAY_FAILED);
        unrestricted.close();
        assertThrows(MusicBusinessException.class, () -> breaker.acquire(MusicErrorCode.UPSTREAM_PLAY_FAILED));
        first.close();
        try (ThirdPartyCircuitBreaker.Attempt second = breaker.acquire(MusicErrorCode.UPSTREAM_PLAY_FAILED)) {
            first.close();
            assertThrows(MusicBusinessException.class, () -> breaker.acquire(MusicErrorCode.UPSTREAM_PLAY_FAILED));
        }
        assertDoesNotThrow(() -> breaker.acquire(MusicErrorCode.UPSTREAM_PLAY_FAILED));
    }

    @Test
    void opensAfterThresholdAndAllowsOneProbeAfterCooldown() {
        AtomicLong clock = new AtomicLong(1_000L);
        ThirdPartyCircuitBreaker breaker =
                new ThirdPartyCircuitBreaker("provider", 3, 60_000L, clock::get);

        for (int i = 0; i < 3; i++) {
            breaker.acquire(MusicErrorCode.UPSTREAM_PLAY_FAILED);
            breaker.recordFailure();
        }

        assertThrows(MusicBusinessException.class,
                () -> breaker.acquire(MusicErrorCode.UPSTREAM_PLAY_FAILED));

        clock.addAndGet(60_000L);
        assertDoesNotThrow(() -> breaker.acquire(MusicErrorCode.UPSTREAM_PLAY_FAILED));
        assertThrows(MusicBusinessException.class,
                () -> breaker.acquire(MusicErrorCode.UPSTREAM_PLAY_FAILED));

        breaker.recordSuccess();
        assertDoesNotThrow(() -> breaker.acquire(MusicErrorCode.UPSTREAM_PLAY_FAILED));
    }
}
