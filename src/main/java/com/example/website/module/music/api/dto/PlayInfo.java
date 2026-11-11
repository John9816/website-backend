package com.example.website.module.music.api.dto;

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
    /** ID on actualSource; null when a text lookup cannot identify the recording. */
    private String actualId;
    /** exact or unverified; original source/id remain stable for library entries. */
    private String lyricMatch;
    private String name;
    private String artist;
    private String album;
    private String coverUrl;
    private Integer durationSec;
    private String playUrl;
    /**
     * Rich-client direct playback URL for Qishui (TuneFree v1) tracks. When present the browser
     * fetches the CENC-encrypted bytes from this upstream CDN URL itself and decrypts them
     * client-side, keeping the audio bytes off our ECS egress entirely. {@code null} for non-Qishui
     * sources and for Qishui when direct resolution is unavailable — callers must fall back to
     * {@link #playUrl}.
     */
    private String directUrl;
    /**
     * CENC play auth (content-key material) needed to decrypt {@link #directUrl} client-side.
     * Short-lived and bound to the track/quality. Only ever populated together with
     * {@link #directUrl}.
     */
    private String playAuth;
    private String requestedQuality;
    private String actualQuality;
    private Long fileSize;
    private Integer expireSec;
    private Boolean fromCache;
    private LyricInfo lyric;
}
