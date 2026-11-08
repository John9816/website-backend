package com.example.website.config;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkHttpConfigTests {

    @Test
    void musicResolverExecutorIsBounded() {
        ThreadPoolTaskExecutor executor = new OkHttpConfig().musicResolverExecutor();
        try {
            assertEquals(8, executor.getCorePoolSize());
            assertEquals(8, executor.getMaxPoolSize());
            assertEquals(16, executor.getThreadPoolExecutor().getQueue().remainingCapacity());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void saturatedResolverPoolNeverRunsNetworkWorkOnRequestThread() throws Exception {
        ThreadPoolTaskExecutor executor = new OkHttpConfig().musicResolverExecutor();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread caller = Thread.currentThread();
        java.util.concurrent.atomic.AtomicBoolean ranOnCaller = new java.util.concurrent.atomic.AtomicBoolean();
        int rejected = 0;
        try {
            for (int i = 0; i < 100; i++) {
                try {
                    executor.execute(() -> {
                        if (Thread.currentThread() == caller) { ranOnCaller.set(true); return; }
                        try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    });
                } catch (java.util.concurrent.RejectedExecutionException expected) { rejected++; }
            }
            assertFalse(ranOnCaller.get(), "saturation must not put network work on request thread");
            assertTrue(rejected > 0, "overload must be explicit");
        } finally { release.countDown(); executor.shutdown(); }
    }

    @Test
    void thirdPartyMusicClientRecordsUpstreamMetricsAndDoesNotRetry() {
        PerformanceMetrics metrics = new PerformanceMetrics();
        UpstreamMetricsInterceptor interceptor = new UpstreamMetricsInterceptor(metrics);

        OkHttpClient client = new OkHttpConfig().musicThirdPartyHttpClient(interceptor);

        assertFalse(client.retryOnConnectionFailure());
        assertTrue(client.interceptors().contains(interceptor));
        assertEquals(5_000L, client.callTimeoutMillis());
    }
}
