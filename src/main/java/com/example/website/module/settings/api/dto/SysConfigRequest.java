package com.example.website.module.settings.api.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class SysConfigRequest {

    @NotBlank(message = "configKey is required")
    @Size(max = 100)
    private String configKey;

    @Size(max = 2000)
    private String configValue;

    /** Keep the current secret while editing its key or description. */
    private Boolean preserveValue;

    @Size(max = 500)
    private String description;
}
