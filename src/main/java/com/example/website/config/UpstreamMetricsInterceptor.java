package com.example.website.config;

import okhttp3.Interceptor;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Records aggregate upstream latency and status classes; URL/query data is intentionally excluded. */
@Component
public class UpstreamMetricsInterceptor implements Interceptor {

    private final PerformanceMetrics metrics;

    public UpstreamMetricsInterceptor(PerformanceMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        long started = System.nanoTime();
        try {
            Response response = chain.proceed(chain.request());
            String statusClass = String.valueOf(response.code() / 100) + "xx";
            metrics.recordUpstream(statusClass, System.nanoTime() - started);
            return response;
        } catch (IOException error) {
            // A hedge winner cancels its losing siblings on purpose. Those StreamResetException /
            // SocketException failures are not upstream faults, so keep them out of the error
            // counter and alerting. Only real errors should be attributed to the provider.
            boolean cancelled = chain.call().isCanceled();
            metrics.recordUpstreamError(cancelled
                    ? "cancelled/" + error.getClass().getSimpleName()
                    : error.getClass().getSimpleName());
            metrics.recordUpstream(cancelled ? "cancelled" : "error",
                    System.nanoTime() - started);
            throw error;
        }
    }
}
