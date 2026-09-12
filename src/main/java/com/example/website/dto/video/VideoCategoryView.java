package com.example.website.dto.video;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class VideoCategoryView {
    private final String id;
    private final String name;
    private final String url;
}
