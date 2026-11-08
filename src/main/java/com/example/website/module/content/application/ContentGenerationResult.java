package com.example.website.module.content.application;

import java.util.List;

@lombok.Data
@lombok.AllArgsConstructor
class ContentGenerationResult {
    private String title;
    private String digest;
    private String markdown;
    private String coverPrompt;
    private List<String> tags;
    private List<String> riskTips;
    private String model;
    private boolean fallbackUsed;
}
