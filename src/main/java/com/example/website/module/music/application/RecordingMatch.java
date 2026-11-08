package com.example.website.module.music.application;

import com.example.website.module.music.api.dto.SongSearchItem;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Conservative cross-platform recording matching: version words are significant. */
final class RecordingMatch {
    private RecordingMatch() { }

    static SongSearchItem pick(List<SongSearchItem> candidates, SongSearchItem target) {
        if (target == null || candidates == null) return null;
        String title = title(target.getName());
        String artist = artist(target.getArtist());
        if (title.isEmpty() || artist.isEmpty()) return null;
        SongSearchItem best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (SongSearchItem candidate : candidates) {
            if (candidate == null || candidate.getId() == null || candidate.getId().trim().isEmpty()
                    || !title.equals(title(candidate.getName()))
                    || !artist.equals(artist(candidate.getArtist()))) continue;
            Integer a = target.getDurationSec();
            Integer b = candidate.getDurationSec();
            int distance = Integer.MAX_VALUE - 1;
            if (a != null && b != null && a > 0 && b > 0) {
                distance = (int) Math.min(Integer.MAX_VALUE, Math.abs((long) a - b));
                double tolerance = Math.min(5.0, Math.max(2.0, a * 0.02));
                if (distance > tolerance) continue;
            }
            if (best == null || distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static String title(String value) {
        // Normalize typography only; never drop parenthesized Live/Remix/version text.
        return normalize(value).replaceAll("[\\[\\](){}]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static String artist(String value) {
        return normalize(value).replace('、', '/').replace('&', '/')
                .replaceAll("\\s*/\\s*", "/").replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
