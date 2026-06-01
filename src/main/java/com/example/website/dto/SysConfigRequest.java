package com.example.website.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class SysConfigRequest {

    @NotBlank(message = "configKey is required")
    @Size(max = 100)
    private String configKey;

    @NotBlank(message = "configValue is required")
    @Size(max = 2000)
    private String configValue;

    @Size(max = 500)
    private String description;
}
