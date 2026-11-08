package com.example.website.shared.concurrent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceSingleFlightTests {

    @Test
    void executesOneLoaderForConcurrentCallersOfTheSameKey() throws Exception {
        PerformanceSingleFlight flight = new PerformanceSingleFlight();
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch ready = new CountDownLatch(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        List<Integer> values = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    int value = flight.execute("same-key", () -> {
                        loads.incrementAndGet();
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
                        return 42;
                    });
                    synchronized (values) {
                        values.add(value);
                    }
                } catch (Throwable error) {
                    synchronized (errors) {
                        errors.add(error);
                    }
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals(0, errors.size());
        assertEquals(10, values.size());
        assertEquals(1, loads.get());
    }

    @Test
    void followerStopsWaitingWhenItsBudgetExpires() throws Exception {
        PerformanceSingleFlight flight = new PerformanceSingleFlight();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        try {
            Future<Integer> leader = executor.submit(() -> flight.execute("slow-key", () -> {
                loaderStarted.countDown();
                try {
                    releaseLoader.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                }
                return 42;
            }));
            assertTrue(loaderStarted.await(1, TimeUnit.SECONDS));

            assertThrows(PerformanceSingleFlight.WaitTimeoutException.class,
                    () -> flight.execute("slow-key", () -> 99, 50, TimeUnit.MILLISECONDS));

            releaseLoader.countDown();
            assertEquals(42, leader.get(1, TimeUnit.SECONDS));
        } finally {
            releaseLoader.countDown();
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }
}
