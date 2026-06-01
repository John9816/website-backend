package com.example.website.dto.kb;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class KbTagRequest {

    @NotBlank(message = "name is required")
    @Size(max = 50)
    private String name;

    @Size(max = 20)
    private String color;
}
