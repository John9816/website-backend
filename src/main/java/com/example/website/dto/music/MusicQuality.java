package com.example.website.dto.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MusicQuality {

    K128("128k"),
    K320("320k"),
    FLAC("flac"),
    FLAC24("flac24bit");

    private final String value;

    MusicQuality(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static MusicQuality of(String raw) {
        if (raw == null || raw.isEmpty()) return FLAC;
        for (MusicQuality q : values()) {
            if (q.value.equalsIgnoreCase(raw)) return q;
        }
        throw new MusicBusinessException(MusicErrorCode.INVALID_QUALITY,
                "invalid quality: " + raw + ", allowed: 128k, 320k, flac, flac24bit");
    }
}
