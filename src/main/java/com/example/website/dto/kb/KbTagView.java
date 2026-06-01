package com.example.website.dto.kb;

import com.example.website.entity.KbTag;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KbTagView {
    private Long id;
    private String name;
    private String color;
    private LocalDateTime createdAt;

    public static KbTagView from(KbTag t) {
        KbTagView v = new KbTagView();
        v.id = t.getId();
        v.name = t.getName();
        v.color = t.getColor();
        v.createdAt = t.getCreatedAt();
        return v;
    }
}
