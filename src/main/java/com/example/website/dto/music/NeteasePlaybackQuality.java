package com.example.website.dto.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;

public enum NeteasePlaybackQuality {

    STANDARD("standard", MusicQuality.K128),
    EXHIGH("exhigh", MusicQuality.K320),
    LOSSLESS("lossless", MusicQuality.FLAC),
    HIRES("hires", MusicQuality.FLAC24),
    JYEFFECT("jyeffect", MusicQuality.FLAC24),
    SKY("sky", MusicQuality.FLAC24),
    JYMASTER("jymaster", MusicQuality.FLAC24);

    private final String value;
    private final MusicQuality legacyQuality;

    NeteasePlaybackQuality(String value, MusicQuality legacyQuality) {
        this.value = value;
        this.legacyQuality = legacyQuality;
    }

    public String getValue() {
        return value;
    }

    public MusicQuality getLegacyQuality() {
        return legacyQuality;
    }

    public static NeteasePlaybackQuality of(String raw) {
        for (NeteasePlaybackQuality quality : values()) {
            if (quality.value.equalsIgnoreCase(raw)) return quality;
        }
        throw new MusicBusinessException(MusicErrorCode.INVALID_QUALITY,
                "invalid NetEase quality: " + raw
                        + ", allowed: standard, exhigh, lossless, hires, jyeffect, sky, jymaster");
    }
}
