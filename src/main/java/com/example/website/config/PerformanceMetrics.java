package com.example.website.config;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Low-cardinality in-process timing counters with no external metrics dependency. */
@Component
public class PerformanceMetrics {

    private final Map<String, Sample> requestSamples = new ConcurrentHashMap<>();
    private final Map<String, Sample> upstreamSamples = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> upstreamErrors = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> cacheHits = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> cacheMisses = new ConcurrentHashMap<>();

    public void recordRequest(String method, String route, int status, long nanos) {
        requestSamples.computeIfAbsent(method + " " + route + " " + status, ignored -> new Sample())
                .record(nanos);
    }

    public void recordUpstream(String status, long nanos) {
        upstreamSamples.computeIfAbsent(status, ignored -> new Sample()).record(nanos);
    }

    public void recordUpstreamError(String type) {
        upstreamErrors.computeIfAbsent(type, ignored -> new LongAdder()).increment();
    }

    public void recordCacheHit(String cache) {
        cacheHits.computeIfAbsent(cache, ignored -> new LongAdder()).increment();
    }

    public void recordCacheMiss(String cache) {
        cacheMisses.computeIfAbsent(cache, ignored -> new LongAdder()).increment();
    }

    public Snapshot snapshot() {
        Map<String, Count> requests = snapshotSamples(requestSamples);
        Map<String, Count> upstream = snapshotSamples(upstreamSamples);
        Map<String, Long> errors = new LinkedHashMap<>();
        upstreamErrors.forEach((key, value) -> errors.put(key, value.sum()));
        return new Snapshot(requests, upstream, errors, longs(cacheHits), longs(cacheMisses));
    }

    private Map<String, Long> longs(Map<String, LongAdder> source) {
        Map<String, Long> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, value.sum()));
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Count> snapshotSamples(Map<String, Sample> source) {
        Map<String, Count> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, new Count(value.count.sum(), value.totalNanos.sum())));
        return Collections.unmodifiableMap(result);
    }

    private static final class Sample {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();

        private void record(long nanos) {
            count.increment();
            totalNanos.add(Math.max(0L, nanos));
        }
    }

    public static final class Count {
        private final long count;
        private final long totalNanos;

        Count(long count, long totalNanos) {
            this.count = count;
            this.totalNanos = totalNanos;
        }

        public long getCount() { return count; }
        public long getTotalNanos() { return totalNanos; }
    }

    public static final class Snapshot {
        private final Map<String, Count> requests;
        private final Map<String, Count> upstream;
        private final Map<String, Long> upstreamErrors;
        private final Map<String, Long> cacheHits;
        private final Map<String, Long> cacheMisses;

        Snapshot(Map<String, Count> requests, Map<String, Count> upstream, Map<String, Long> upstreamErrors,
                 Map<String, Long> cacheHits, Map<String, Long> cacheMisses) {
            this.requests = requests;
            this.upstream = upstream;
            this.upstreamErrors = upstreamErrors;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
        }

        public Map<String, Count> getRequests() { return requests; }
        public Map<String, Count> getUpstream() { return upstream; }
        public Map<String, Long> getUpstreamErrors() { return upstreamErrors; }
        public Map<String, Long> getCacheHits() { return cacheHits; }
        public Map<String, Long> getCacheMisses() { return cacheMisses; }
    }
}
