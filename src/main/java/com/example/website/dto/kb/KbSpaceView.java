package com.example.website.dto.kb;

import com.example.website.entity.KbSpace;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KbSpaceView {
    private Long id;
    private String name;
    private String description;
    private String icon;
    private Integer sortOrder;
    private Integer docCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static KbSpaceView from(KbSpace s) {
        KbSpaceView v = new KbSpaceView();
        v.id = s.getId();
        v.name = s.getName();
        v.description = s.getDescription();
        v.icon = s.getIcon();
        v.sortOrder = s.getSortOrder();
        v.docCount = s.getDocCount();
        v.createdAt = s.getCreatedAt();
        v.updatedAt = s.getUpdatedAt();
        return v;
    }
}
