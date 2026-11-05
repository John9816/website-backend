package com.example.website.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Coalesces concurrent cache misses for one key without changing callers' sync APIs. */
@Component
public class PerformanceSingleFlight {

    private final ConcurrentMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T execute(String key, Supplier<T> loader) {
        return executeInternal(key, loader, null, null);
    }

    @SuppressWarnings("unchecked")
    public <T> T execute(String key, Supplier<T> loader, long timeout, TimeUnit unit) {
        if (unit == null) throw new IllegalArgumentException("unit is required");
        if (timeout <= 0L) throw new WaitTimeoutException("single-flight wait budget exhausted");
        return executeInternal(key, loader, timeout, unit);
    }

    @SuppressWarnings("unchecked")
    private <T> T executeInternal(String key, Supplier<T> loader, Long timeout, TimeUnit unit) {
        CompletableFuture<Object> created = new CompletableFuture<>();
        CompletableFuture<Object> future = inFlight.putIfAbsent(key, created);
        if (future == null) {
            future = created;
            try {
                future.complete(loader.get());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            } finally {
                inFlight.remove(key, future);
            }
        }
        try {
            return timeout == null
                    ? (T) future.join()
                    : (T) future.get(timeout, unit);
        } catch (TimeoutException error) {
            throw new WaitTimeoutException("single-flight wait timed out", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new WaitTimeoutException("single-flight wait interrupted", error);
        } catch (ExecutionException error) {
            return rethrow(error.getCause());
        } catch (java.util.concurrent.CompletionException error) {
            return rethrow(error.getCause());
        }
    }

    private static <T> T rethrow(Throwable cause) {
        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
        if (cause instanceof Error) throw (Error) cause;
        throw new java.util.concurrent.CompletionException(cause);
    }

    public static final class WaitTimeoutException extends RuntimeException {
        public WaitTimeoutException(String message) {
            super(message);
        }

        public WaitTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
