package com.example.website.dto.content;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
@AllArgsConstructor
public class ContentAutomationView {
    private String currentStage;
    private List<Object> logs;
    private List<Object> jobs;
    private List<Object> publishRecords;

    public static ContentAutomationView empty() {
        return new ContentAutomationView(null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }
}
