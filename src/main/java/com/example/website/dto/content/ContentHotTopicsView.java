package com.example.website.dto.content;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
public class ContentHotTopicsView {
    private LocalDateTime capturedAt;
    private List<Map<String, Object>> sources;
    private List<Map<String, Object>> items;
}
