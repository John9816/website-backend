package com.example.website.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestMetricsInterceptorTests {

    @Test
    void normalizesDynamicPathSegmentsToBoundedRouteTags() {
        RequestMetricsInterceptor interceptor = new RequestMetricsInterceptor(new PerformanceMetrics());

        assertEquals("/api/v1/music/{id}/play", interceptor.normalizePath("/api/v1/music/12345/play"));
        assertEquals("/api/public/kb/share/{token}", interceptor.normalizePath("/api/public/kb/share/abcdef1234567890"));
        assertTrue(interceptor.normalizePath("/api/public/nav").length() < 64);
    }

    @Test
    void recordsCompletedRequestTimerWithStatus() throws Exception {
        PerformanceMetrics registry = new PerformanceMetrics();
        RequestMetricsInterceptor interceptor = new RequestMetricsInterceptor(registry);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/nav");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertEquals(1, registry.snapshot().getRequests().values().iterator().next().getCount());
    }

    @Test
    void recordsUpstreamStatusAndErrorsWithoutUrlTags() throws IOException {
        PerformanceMetrics metrics = new PerformanceMetrics();
        UpstreamMetricsInterceptor interceptor = new UpstreamMetricsInterceptor(metrics);
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        Request request = new Request.Builder().url("https://example.com/private?token=secret").build();
        when(chain.request()).thenReturn(request);
        when(chain.proceed(any())).thenReturn(new Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create("{}", okhttp3.MediaType.get("application/json"))).build());

        interceptor.intercept(chain);

        assertEquals(1, metrics.snapshot().getUpstream().get("2xx").getCount());
        assertEquals(false, metrics.snapshot().getUpstream().keySet().stream()
                .anyMatch(key -> key.contains("example.com") || key.contains("secret")));
    }
}
