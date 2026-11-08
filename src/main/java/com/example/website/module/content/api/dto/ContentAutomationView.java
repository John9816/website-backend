package com.example.website.module.content.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
public class ContentAutomationView {
    private String currentStage;
    private List<Object> logs;
    private List<Object> jobs;
    private List<Object> publishRecords;
    /** TrendPublish-style run artifacts: evidence, plan, review, markdown/html and cover snapshots. */
    private List<Object> artifacts;
    /** Quality gate summary derived from review score and risk tips. */
    private Map<String, Object> qualityGate;

    public ContentAutomationView(String currentStage,
                                 List<Object> logs,
                                 List<Object> jobs,
                                 List<Object> publishRecords) {
        this(currentStage, logs, jobs, publishRecords, Collections.emptyList(), null);
    }

    public static ContentAutomationView empty() {
        return new ContentAutomationView(
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                null
        );
    }
}
