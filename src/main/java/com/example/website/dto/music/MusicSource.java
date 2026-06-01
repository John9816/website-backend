package com.example.website.dto.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MusicSource {

    QQ("qq", "qq"),
    NETEASE("netease", "netease"),
    KUWO("kuwo", "kw");

    private final String value;
    private final String tfPayPlatform;

    MusicSource(String value, String tfPayPlatform) {
        this.value = value;
        this.tfPayPlatform = tfPayPlatform;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * The value to send as {@code platform=} to tf-pay. Note kuwo's upstream
     * key is {@code kw}, not {@code kuwo}.
     */
    public String getTfPayPlatform() {
        return tfPayPlatform;
    }

    public static MusicSource of(String raw) {
        if (raw == null) {
            throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE,
                    "source is required, allowed: qq, netease, kuwo");
        }
        for (MusicSource s : values()) {
            if (s.value.equalsIgnoreCase(raw)) return s;
        }
        throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE,
                "invalid source: " + raw + ", allowed: qq, netease, kuwo");
    }
}
