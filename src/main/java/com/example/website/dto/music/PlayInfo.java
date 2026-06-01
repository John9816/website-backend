package com.example.website.dto.music;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayInfo {

    private String id;
    private MusicSource source;
    /**
     * Source that actually served the audio. Differs from {@link #source}
     * when the backend fell back to another platform because the requested
     * one had no playable URL (e.g. copyright / geo block).
     */
    private MusicSource actualSource;
    private String name;
    private String artist;
    private String album;
    private String coverUrl;
    private Integer durationSec;
    private String playUrl;
    private String requestedQuality;
    private String actualQuality;
    private Long fileSize;
    private Integer expireSec;
    private Boolean fromCache;
    private LyricInfo lyric;
}
