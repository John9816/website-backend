package com.example.website.module.identity.api.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class PasswordResetRequest {

    @NotBlank(message = "email is required")
    @Size(max = 100, message = "email length must be at most 100")
    @Pattern(regexp = "^(?:[1-9]\\d{4,10}@qq\\.com|[a-z0-9][a-z0-9._-]{0,63}@751152\\.xyz)$",
            flags = Pattern.Flag.CASE_INSENSITIVE,
            message = "email must be a valid QQ or 751152.xyz email")
    private String email;

    @NotBlank(message = "verificationCode is required")
    @Pattern(regexp = "\\d{6}", message = "verificationCode must be 6 digits")
    private String verificationCode;

    @NotBlank(message = "newPassword is required")
    @Size(min = 6, max = 64, message = "newPassword length must be 6-64")
    private String newPassword;
}
