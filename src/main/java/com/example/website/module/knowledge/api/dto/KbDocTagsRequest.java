package com.example.website.module.knowledge.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class KbDocTagsRequest {
    private List<Long> tagIds;
}
