package com.example.website.dto;

import com.example.website.entity.NavLink;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LinkView {
    private Long id;
    private Long categoryId;
    private String name;
    private String url;
    private String description;
    private String icon;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static LinkView from(NavLink n) {
        LinkView v = new LinkView();
        v.id = n.getId();
        v.categoryId = n.getCategoryId();
        v.name = n.getName();
        v.url = n.getUrl();
        v.description = n.getDescription();
        v.icon = n.getIcon();
        v.sortOrder = n.getSortOrder();
        v.createdAt = n.getCreatedAt();
        v.updatedAt = n.getUpdatedAt();
        return v;
    }
}
