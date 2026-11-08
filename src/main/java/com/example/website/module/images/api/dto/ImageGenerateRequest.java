package com.example.website.module.images.api.dto;

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

    @Size(max = 100)
    private String model;

    @Min(1)
    @Max(3)
    private Integer n;

    @Pattern(
            regexp = "(?i)(auto|(?:\\d+(?:x|\\*)\\d+)|(?:1:1|4:5|3:4|2:3|3:2|4:3|16:9|9:16|21:9))",
            message = "size must be auto, a supported ratio, WIDTHxHEIGHT, or WIDTH*HEIGHT"
    )
    private String size;
}
