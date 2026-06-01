package com.example.website.dto.kb;

import lombok.Data;

import java.util.List;

@Data
public class KbDocTagsRequest {
    private List<Long> tagIds;
}
