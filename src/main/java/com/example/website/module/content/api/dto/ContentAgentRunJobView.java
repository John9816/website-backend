package com.example.website.module.content.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContentAgentRunJobView {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    /** Coarse pipeline stage for UI progress (research/plan/write/review/visual/gate/done). */
    public static final String STAGE_QUEUED = "queued";
    public static final String STAGE_TOPIC = "topic";
    public static final String STAGE_RESEARCH = "research";
    public static final String STAGE_PLAN = "plan";
    public static final String STAGE_WRITE = "write";
    public static final String STAGE_REVIEW = "review";
    public static final String STAGE_VISUAL = "visual";
    public static final String STAGE_GATE = "gate";
    public static final String STAGE_DONE = "done";
    public static final String STAGE_FAILED = "failed";

    private String jobId;
    private String status;
    private String stage;
    private String stageLabel;
    private String errorMessage;
    private ContentArticleView article;
    private ContentAutomationView automation;
    private Map<String, Object> topic;
    private Map<String, Object> draft;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean finished() {
        return STATUS_SUCCESS.equals(status) || STATUS_FAILED.equals(status);
    }
}
