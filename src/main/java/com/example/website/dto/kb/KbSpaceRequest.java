package com.example.website.dto.kb;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class KbSpaceRequest {

    @NotBlank(message = "name is required")
    @Size(max = 100)
    private String name;

    @Size(max = 500)
    private String description;

    @Size(max = 255)
    private String icon;

    private Integer sortOrder;
}
