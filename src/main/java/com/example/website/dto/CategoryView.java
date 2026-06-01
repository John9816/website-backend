package com.example.website.dto;

import com.example.website.entity.Category;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CategoryView {
    private Long id;
    private String name;
    private String icon;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<LinkView> links;

    public static CategoryView from(Category c) {
        CategoryView v = new CategoryView();
        v.id = c.getId();
        v.name = c.getName();
        v.icon = c.getIcon();
        v.sortOrder = c.getSortOrder();
        v.createdAt = c.getCreatedAt();
        v.updatedAt = c.getUpdatedAt();
        return v;
    }
}
