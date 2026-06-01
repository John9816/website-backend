package com.example.website.dto;

import com.example.website.entity.ImageGenerationTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageTaskView {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Long id;
    private String prompt;
    private String size;
    private Integer n;
    private String model;
    private String status;
    private String errorMessage;
    private ImageGenerationsResponse result;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    public static ImageTaskView from(ImageGenerationTask t) {
        ImageGenerationsResponse result = null;
        if (t.getResultJson() != null) {
            try {
                result = MAPPER.readValue(t.getResultJson(), ImageGenerationsResponse.class);
            } catch (Exception ignore) {
            }
        }
        return new ImageTaskView(
                t.getId(), t.getPrompt(), t.getSize(), t.getN(), t.getModel(),
                t.getStatus(), t.getErrorMessage(), result,
                t.getCreatedAt(), t.getUpdatedAt(), t.getCompletedAt()
        );
    }
}
