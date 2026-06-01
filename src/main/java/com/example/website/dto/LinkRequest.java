package com.example.website.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class LinkRequest {

    @NotNull(message = "categoryId is required")
    private Long categoryId;

    @NotBlank(message = "name is required")
    @Size(max = 100)
    private String name;

    @NotBlank(message = "url is required")
    @Size(max = 500)
    private String url;

    @Size(max = 500)
    private String description;

    @Size(max = 500)
    private String icon;

    private Integer sortOrder;
}
