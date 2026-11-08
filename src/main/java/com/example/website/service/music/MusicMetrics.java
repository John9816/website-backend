package com.example.website.service.music;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Music endpoint measurements whose tags can never contain request identifiers. */
@Component
public class MusicMetrics {

    private static final Set<String> SOURCES = values("qq", "netease", "kuwo");
    private static final Set<String> OPERATIONS = values("search", "play", "lyric", "cover", "history",
            "lyric_official", "lyric_fallback", "metadata");
    private static final Set<String> OUTCOMES = values("success", "failure", "timeout", "rejected",
            "cache_hit", "fallback", "cooldown", "cancelled", "queued", "duplicate", "retry", "discarded");
    private static final Set<String> PROVIDERS = values("qq", "netease", "kuwo", "cyapi", "tunefree");
    private static final Set<String> GRANULARITIES = values("word", "line", "none");

    private final MeterRegistry registry;

    public MusicMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String source, String operation, String outcome, long startedNanos) {
        String safeSource = bounded(source, SOURCES);
        String safeOperation = bounded(operation, OPERATIONS);
        String safeOutcome = bounded(outcome, OUTCOMES);
        String[] tags = {"source", safeSource, "operation", safeOperation, "outcome", safeOutcome};
        registry.counter("music.requests", tags).increment();
        Timer.builder("music.request.duration")
                .tags(tags)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(8))
                .register(registry)
                .record(Math.max(0L, System.nanoTime() - startedNanos), TimeUnit.NANOSECONDS);
    }

    public void cache(String source, String operation, boolean hit) {
        registry.counter("music.cache.requests", "source", bounded(source, SOURCES),
                "operation", bounded(operation, OPERATIONS), "result", hit ? "hit" : "miss").increment();
    }

    public void lyric(String source, String provider, String granularity) {
        registry.counter("music.lyrics", "source", bounded(source, SOURCES),
                "provider", bounded(provider, PROVIDERS),
                "granularity", bounded(granularity, GRANULARITIES)).increment();
    }

    public void rejected(String source, String operation) {
        Counter.builder("music.rejections")
                .tags("source", bounded(source, SOURCES), "operation", bounded(operation, OPERATIONS))
                .register(registry).increment();
    }

    private static Set<String> values(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private static String bounded(String value, Set<String> allowed) {
        String normalised = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return allowed.contains(normalised) ? normalised : "unknown";
    }
}
