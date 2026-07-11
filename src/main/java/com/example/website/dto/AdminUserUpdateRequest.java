package com.example.website.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

@Data
public class AdminUserUpdateRequest {

    @NotBlank(message = "role is required")
    @Pattern(regexp = "^(ADMIN|USER)$", message = "role must be ADMIN or USER")
    private String role;

    @NotNull(message = "enabled is required")
    private Boolean enabled;
}
