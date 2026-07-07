package com.example.website.dto.content;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContentHotTopicView {
    private String id;
    private String source;
    private String sourceName;
    private int rank;
    private String title;
    private String url;
    private String hot;
    private String summary;
    private LocalDateTime capturedAt;

    @Data
    @AllArgsConstructor
    public static class Source {
        private String id;
        private String name;
    }

    @Data
    @AllArgsConstructor
    public static class HotTopics {
        private LocalDateTime capturedAt;
        private List<Source> sources;
        private List<ContentHotTopicView> items;
    }
}
