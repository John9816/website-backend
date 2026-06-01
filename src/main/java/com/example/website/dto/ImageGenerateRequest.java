package com.example.website.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class ImageGenerateRequest {

    @NotBlank(message = "prompt is required")
    @Size(max = 2000)
    private String prompt;

    @Min(1)
    @Max(10)
    private Integer n;

    @Pattern(regexp = "\\d+x\\d+", message = "size must be WIDTHxHEIGHT, e.g. 1024x1024")
    private String size;
}
