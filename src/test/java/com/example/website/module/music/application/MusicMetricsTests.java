package com.example.website.module.music.application;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicMetricsTests {

    @Test
    void recordsRequestDurationAndResultAlongsideCacheLyricsAndRejectionCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MusicMetrics metrics = new MusicMetrics(registry);

        metrics.record("qq", "lyric", "success", System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(10));
        metrics.cache("qq", "lyric", true);
        metrics.cache("qq", "lyric", false);
        metrics.lyric("qq", "qq", "word");
        metrics.rejected("qq", "play");

        assertEquals(1.0, registry.get("music.requests").tags("source", "qq", "operation", "lyric", "outcome", "success").counter().count());
        assertEquals(1L, registry.get("music.request.duration").tags("source", "qq", "operation", "lyric", "outcome", "success").timer().count());
        assertTrue(registry.get("music.request.duration").timer().totalTime(TimeUnit.MILLISECONDS) >= 10.0);
        assertEquals(1.0, registry.get("music.cache.requests").tag("result", "hit").counter().count());
        assertEquals(1.0, registry.get("music.cache.requests").tag("result", "miss").counter().count());
        assertEquals(1.0, registry.get("music.lyrics").tags("source", "qq", "provider", "qq", "granularity", "word").counter().count());
        assertEquals(1.0, registry.get("music.rejections").tags("source", "qq", "operation", "play").counter().count());
    }

    @Test
    void arbitraryMetricInputsCollapseToFiniteUnknownLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MusicMetrics metrics = new MusicMetrics(registry);
        for (int i = 0; i < 100; i++) {
            metrics.record("song-" + i, "https://example.com/" + i, "user-" + i, System.nanoTime());
            metrics.cache("song-" + i, "keyword-" + i, true);
            metrics.lyric("song-" + i, "https://lyrics/" + i, "word-" + i);
            metrics.rejected("song-" + i, "user-" + i);
        }

        assertEquals(5, registry.getMeters().size());
        Set<String> allowed = new HashSet<>(Arrays.asList("unknown", "hit"));
        for (Meter meter : registry.getMeters()) {
            meter.getId().getTags().forEach(tag -> assertTrue(allowed.contains(tag.getValue()), tag.toString()));
        }
        assertEquals(100.0, registry.get("music.requests").tags("source", "unknown", "operation", "unknown", "outcome", "unknown").counter().count());
    }
}
