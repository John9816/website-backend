package com.example.website.module.video.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class VideoPlayView {
    private final String videoId;
    private final String episodeId;
    private final String lineId;
    private final String mediaUrl;
    private final String format;
    private final String embedUrl;
    private final String upstreamUrl;
    private final boolean playable;
    private final String message;
}
