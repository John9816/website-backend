package com.example.website.module.images.api.dto;

import com.example.website.module.images.domain.GeneratedImage;
import com.example.website.module.images.infrastructure.persistence.GeneratedImageRepository.GeneratedImageSummary;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class GeneratedImageView {

    private Long id;
    private String type;
    private String prompt;
    private String imageUrl;
    private String model;
    private String size;
    private Boolean isShared;
    private LocalDateTime createdAt;

    public static GeneratedImageView from(GeneratedImage e) {
        return new GeneratedImageView(
                e.getId(),
                normalizeType(e.getType()),
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
                normalizeType(s.getType()),
                s.getPrompt(),
                s.getImageUrl(),
                s.getModel(),
                s.getSize(),
                s.getIsShared(),
                s.getCreatedAt()
        );
    }

    private static String normalizeType(String type) {
        return type == null || type.trim().isEmpty() ? "image" : type.trim();
    }
}
