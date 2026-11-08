package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.MusicQuality;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.NeteasePlaybackQuality;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaybackQualityRequestTests {

    @ParameterizedTest
    @CsvSource({
            "standard, STANDARD, K128",
            "exhigh, EXHIGH, K320",
            "lossless, LOSSLESS, FLAC",
            "hires, HIRES, FLAC24",
            "jyeffect, JYEFFECT, FLAC24",
            "sky, SKY, FLAC24",
            "jymaster, JYMASTER, FLAC24"
    })
    void parsesEveryNeteaseQuality(String raw,
                                   NeteasePlaybackQuality expectedNetease,
                                   MusicQuality expectedLegacy) {
        PlaybackQualityRequest request = PlaybackQualityRequest.parse(MusicSource.NETEASE, raw);

        assertEquals(raw, request.getRequestedValue());
        assertEquals(raw, request.getCacheValue());
        assertEquals(expectedNetease, request.getNeteaseQuality());
        assertEquals(expectedLegacy, request.getLegacyQuality());
    }

    @ParameterizedTest
    @CsvSource({
            "128k, standard, STANDARD, K128",
            "320k, exhigh, EXHIGH, K320",
            "flac, lossless, LOSSLESS, FLAC",
            "flac24bit, hires, HIRES, FLAC24"
    })
    void acceptsLegacyNeteaseAliases(String raw,
                                     String expectedCache,
                                     NeteasePlaybackQuality expectedNetease,
                                     MusicQuality expectedLegacy) {
        PlaybackQualityRequest request = PlaybackQualityRequest.parse(MusicSource.NETEASE, raw);

        assertEquals(raw, request.getRequestedValue());
        assertEquals(expectedCache, request.getCacheValue());
        assertEquals(expectedNetease, request.getNeteaseQuality());
        assertEquals(expectedLegacy, request.getLegacyQuality());
    }

    @Test
    void defaultsNeteaseToLossless() {
        PlaybackQualityRequest request = PlaybackQualityRequest.parse(MusicSource.NETEASE, null);

        assertEquals("lossless", request.getRequestedValue());
        assertEquals("lossless", request.getCacheValue());
        assertEquals(NeteasePlaybackQuality.LOSSLESS, request.getNeteaseQuality());
        assertEquals(MusicQuality.FLAC, request.getLegacyQuality());
    }

    @Test
    void defaultsQqToHighQuality() {
        PlaybackQualityRequest request = PlaybackQualityRequest.parse(MusicSource.QQ, null);

        assertEquals("320k", request.getRequestedValue());
        assertEquals("320k", request.getCacheValue());
        assertNull(request.getNeteaseQuality());
        assertEquals(MusicQuality.K320, request.getLegacyQuality());
    }

    @Test
    void keepsLegacyModelForOtherSources() {
        PlaybackQualityRequest request = PlaybackQualityRequest.parse(MusicSource.QQ, "FLAC24BIT");

        assertEquals("flac24bit", request.getRequestedValue());
        assertEquals("flac24bit", request.getCacheValue());
        assertNull(request.getNeteaseQuality());
        assertEquals(MusicQuality.FLAC24, request.getLegacyQuality());
    }

    @Test
    void rejectsNeteaseOnlyQualityForOtherSources() {
        MusicBusinessException error = assertThrows(MusicBusinessException.class,
                () -> PlaybackQualityRequest.parse(MusicSource.KUWO, "jymaster"));

        assertEquals(MusicErrorCode.INVALID_QUALITY, error.getErrorCode());
    }
}
