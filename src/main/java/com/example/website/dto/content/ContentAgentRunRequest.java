package com.example.website.dto.content;

import lombok.Data;

@Data
public class ContentAgentRunRequest {
    private String category;
    private String topic;
    private String instruction;
    private String length;
    private Boolean generateCover;
    private Boolean autoWechatDraft;
}
