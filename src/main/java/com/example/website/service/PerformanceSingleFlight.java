package com.example.website.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** Coalesces concurrent cache misses for one key without changing callers' sync APIs. */
@Component
public class PerformanceSingleFlight {

    private final ConcurrentMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T execute(String key, Supplier<T> loader) {
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
            return (T) future.join();
        } catch (java.util.concurrent.CompletionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        }
    }
}
