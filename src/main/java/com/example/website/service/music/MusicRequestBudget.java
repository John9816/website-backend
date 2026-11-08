package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import okhttp3.Call;

import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-local deadline shared by the synchronous stages of one play request. */
final class MusicRequestBudget {

    private static final ThreadLocal<Long> DEADLINE_NANOS = new ThreadLocal<>();
    private static final ThreadLocal<CallGroup> CALLS = new ThreadLocal<>();

    private MusicRequestBudget() {
    }

    static Scope start(long duration, TimeUnit unit) {
        Long previous = DEADLINE_NANOS.get();
        long requested = System.nanoTime() + unit.toNanos(duration);
        long deadline = previous == null ? requested : Math.min(previous, requested);
        DEADLINE_NANOS.set(deadline);
        return new Scope(previous);
    }

    static long deadlineNanos() {
        Long deadline = DEADLINE_NANOS.get();
        if (deadline == null) {
            throw new IllegalStateException("music request budget has not been started");
        }
        return deadline;
    }

    static Scope useDeadline(long deadlineNanos) {
        Long previous = DEADLINE_NANOS.get();
        long deadline = previous == null ? deadlineNanos : Math.min(previous, deadlineNanos);
        DEADLINE_NANOS.set(deadline);
        return new Scope(previous);
    }

    static Scope useDeadline(long deadlineNanos, CallGroup calls) {
        Scope scope = useDeadline(deadlineNanos);
        scope.previousCalls = CALLS.get();
        scope.restoresCalls = true;
        CALLS.set(calls);
        return scope;
    }

    static long remainingNanos() {
        Long deadline = DEADLINE_NANOS.get();
        return deadline == null ? Long.MAX_VALUE : Math.max(0L, deadline - System.nanoTime());
    }

    static void ensureRemaining() {
        Long deadline = DEADLINE_NANOS.get();
        if (isCancelled()
                || (deadline != null && deadline - System.nanoTime() <= 0L)) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT,
                    "music request cancelled or exceeded its time budget");
        }
    }

    static boolean isCancelled() {
        CallGroup calls = CALLS.get();
        return Thread.currentThread().isInterrupted() || (calls != null && calls.cancelled.get());
    }

    /** An OkHttp timeout also cancels its Call; only explicit caller cancellation is neutral. */
    static void ensureNotCancelled() {
        if (isCancelled()) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT, "music request cancelled");
        }
    }

    static void constrain(Call call, long maximum, TimeUnit unit) {
        ensureRemaining();
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
        register(call);
    }

    static void constrainToRemaining(Call call) {
        ensureRemaining();
        Long deadline = DEADLINE_NANOS.get();
        if (deadline == null) return;
        long allowed = deadline - System.nanoTime();
        if (allowed <= 0L) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT,
                    "music play resolution exceeded its time budget");
        }
        call.timeout().timeout(allowed, TimeUnit.NANOSECONDS);
        register(call);
    }

    private static void register(Call call) {
        CallGroup calls = CALLS.get();
        if (calls != null) {
            calls.calls.add(call);
            if (calls.cancelled.get()) {
                call.cancel();
                throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT);
            }
        }
    }

    static final class CallGroup {
        private final Set<Call> calls = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        void cancel() {
            cancelled.set(true);
            for (Call call : calls) call.cancel();
            calls.clear();
        }
    }

    static final class Scope implements AutoCloseable {
        private final Long previous;
        private boolean closed;
        private boolean restoresCalls;
        private CallGroup previousCalls;

        private Scope(Long previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) DEADLINE_NANOS.remove();
            else DEADLINE_NANOS.set(previous);
            if (restoresCalls) {
                if (previousCalls == null) CALLS.remove();
                else CALLS.set(previousCalls);
            }
        }
    }
}
