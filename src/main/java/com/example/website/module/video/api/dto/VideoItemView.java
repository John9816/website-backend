package com.example.website.module.video.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class VideoItemView {
    private final String id;
    private final String title;
    private final String posterUrl;
    private final String detailUrl;
    private final String status;
    private final String description;
}
