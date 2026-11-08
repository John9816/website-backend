package com.example.website.module.video.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class VideoFilterView {
    private final List<String> years;
    private final List<String> regions;
    private final List<String> genres;
    private final List<String> orders;
}
