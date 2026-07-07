package com.example.website.dto.content;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class ContentAgentRunResult {
    private ContentArticleView article;
    private ContentAutomationView automation;
    private Map<String, Object> topic;
    private Map<String, Object> draft;
}
