package com.example.website.dto;

import com.example.website.entity.GeneratedImage;
import com.example.website.repository.GeneratedImageRepository.GeneratedImageSummary;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class GeneratedImageView {

    private Long id;
    private String prompt;
    private String imageUrl;
    private String model;
    private String size;
    private Boolean isShared;
    private LocalDateTime createdAt;

    public static GeneratedImageView from(GeneratedImage e) {
        return new GeneratedImageView(
                e.getId(),
                e.getPrompt(),
                e.getImageUrl(),
                e.getModel(),
                e.getSize(),
                e.getIsShared(),
                e.getCreatedAt()
        );
    }

    public static GeneratedImageView from(GeneratedImageSummary s) {
        return new GeneratedImageView(
                s.getId(),
                s.getPrompt(),
                s.getImageUrl(),
                s.getModel(),
                s.getSize(),
                s.getIsShared(),
                s.getCreatedAt()
        );
    }
}
