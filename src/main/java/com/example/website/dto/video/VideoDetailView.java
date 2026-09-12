package com.example.website.dto.video;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class VideoDetailView {
    private final String id;
    private final String title;
    private final String posterUrl;
    private final String description;
    private final List<VideoTagView> tags;
    private final List<VideoLineView> lines;
    private final List<VideoEpisodeView> episodes;
    private final String detailUrl;
}
