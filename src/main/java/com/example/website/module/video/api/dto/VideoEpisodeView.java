package com.example.website.module.video.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class VideoEpisodeView {
    private final String id;
    private final String name;
    private final String url;
}
