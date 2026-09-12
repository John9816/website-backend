package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import okhttp3.Call;

import java.util.concurrent.TimeUnit;

/** Thread-local deadline shared by the synchronous stages of one play request. */
final class MusicRequestBudget {

    private static final ThreadLocal<Long> DEADLINE_NANOS = new ThreadLocal<>();

    private MusicRequestBudget() {
    }

    static Scope start(long duration, TimeUnit unit) {
        Long previous = DEADLINE_NANOS.get();
        long requested = System.nanoTime() + unit.toNanos(duration);
        long deadline = previous == null ? requested : Math.min(previous, requested);
        DEADLINE_NANOS.set(deadline);
        return new Scope(previous);
    }

    static void ensureRemaining() {
        Long deadline = DEADLINE_NANOS.get();
        if (deadline != null && deadline - System.nanoTime() <= 0L) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT,
                    "music play resolution exceeded its time budget");
        }
    }

    static void constrain(Call call, long maximum, TimeUnit unit) {
        Long deadline = DEADLINE_NANOS.get();
        long allowed = unit.toNanos(maximum);
        if (deadline != null) {
            allowed = Math.min(allowed, deadline - System.nanoTime());
        }
        if (allowed <= 0L) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT,
                    "music play resolution exceeded its time budget");
        }
        call.timeout().timeout(allowed, TimeUnit.NANOSECONDS);
    }

    static final class Scope implements AutoCloseable {
        private final Long previous;
        private boolean closed;

        private Scope(Long previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) DEADLINE_NANOS.remove();
            else DEADLINE_NANOS.set(previous);
        }
    }
}
