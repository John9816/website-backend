package com.example.website.service.music;

import com.example.website.dto.music.MusicQuality;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.NeteasePlaybackQuality;

import java.util.Locale;

final class PlaybackQualityRequest {

    private final String requestedValue;
    private final String cacheValue;
    private final NeteasePlaybackQuality neteaseQuality;
    private final MusicQuality legacyQuality;

    private PlaybackQualityRequest(String requestedValue,
                                   String cacheValue,
                                   NeteasePlaybackQuality neteaseQuality,
                                   MusicQuality legacyQuality) {
        this.requestedValue = requestedValue;
        this.cacheValue = cacheValue;
        this.neteaseQuality = neteaseQuality;
        this.legacyQuality = legacyQuality;
    }

    static PlaybackQualityRequest parse(MusicSource source, String raw) {
        if (source != MusicSource.NETEASE) {
            String requested = normalize(raw);
            MusicQuality legacy = source == MusicSource.QQ && requested == null
                    ? MusicQuality.K320
                    : MusicQuality.of(requested);
            return new PlaybackQualityRequest(legacy.getValue(), legacy.getValue(), null, legacy);
        }

        String requested = normalize(raw);
        if (requested == null) requested = NeteasePlaybackQuality.LOSSLESS.getValue();
        String canonical = canonicalNeteaseValue(requested);
        NeteasePlaybackQuality netease = NeteasePlaybackQuality.of(canonical);
        return new PlaybackQualityRequest(requested, netease.getValue(), netease, netease.getLegacyQuality());
    }

    private static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String canonicalNeteaseValue(String raw) {
        if (MusicQuality.K128.getValue().equals(raw)) return NeteasePlaybackQuality.STANDARD.getValue();
        if (MusicQuality.K320.getValue().equals(raw)) return NeteasePlaybackQuality.EXHIGH.getValue();
        if (MusicQuality.FLAC.getValue().equals(raw)) return NeteasePlaybackQuality.LOSSLESS.getValue();
        if (MusicQuality.FLAC24.getValue().equals(raw)) return NeteasePlaybackQuality.HIRES.getValue();
        return raw;
    }

    String getRequestedValue() {
        return requestedValue;
    }

    String getCacheValue() {
        return cacheValue;
    }

    NeteasePlaybackQuality getNeteaseQuality() {
        return neteaseQuality;
    }

    MusicQuality getLegacyQuality() {
        return legacyQuality;
    }
}
