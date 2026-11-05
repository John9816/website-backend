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
            assertEquals(4, executor.getCorePoolSize());
            assertEquals(8, executor.getMaxPoolSize());
            assertEquals(64, executor.getThreadPoolExecutor().getQueue().remainingCapacity());
        } finally {
            executor.shutdown();
        }
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
