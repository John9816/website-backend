package com.example.website.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class CategoryRequest {

    @NotBlank(message = "name is required")
    @Size(max = 100)
    private String name;

    @Size(max = 255)
    private String icon;

    private Integer sortOrder;
}
