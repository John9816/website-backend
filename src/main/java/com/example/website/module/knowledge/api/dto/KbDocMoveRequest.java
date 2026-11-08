package com.example.website.module.knowledge.api.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class KbDocMoveRequest {

    @NotNull(message = "spaceId is required")
    private Long spaceId;

    private Long parentId;

    private Integer sortOrder;
}
