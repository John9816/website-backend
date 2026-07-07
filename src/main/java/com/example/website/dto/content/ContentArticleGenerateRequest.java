package com.example.website.dto.content;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ContentArticleGenerateRequest {
    private List<Map<String, Object>> topics;
    private String topic;
    private String category;
    private String layoutTheme;
    private String imageMode;
    private Boolean researchEnabled;
    private String researchDepth;
    private List<String> searchQueries;
    private Boolean autoWechatDraft;
    private Boolean autoPublish;
    private String angle;
    private String audience;
    private String tone;
    private String length;
    private Boolean generateCover;
    private String coverStyle;
    private String model;
}
