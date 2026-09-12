package com.example.website.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
