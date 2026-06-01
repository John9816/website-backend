package com.example.website.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerationsResponse {

    private Long created;
    private String model;
    private List<ImageDataItem> data;
    private Map<String, Object> usage;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageDataItem {
        private String url;
        private String b64Json;
        private String revisedPrompt;
    }
}
