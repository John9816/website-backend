package com.example.website.dto.video;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class VideoSearchPageView {
    private final List<VideoItemView> list;
    private final int page;
    private final int pageSize;
    private final int total;
    private final boolean hasMore;
}
