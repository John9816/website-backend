package com.example.website.module.identity.api.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class LoginRequest {

    @Size(max = 100, message = "username length must be at most 100")
    private String username;

    @Size(max = 100, message = "email length must be at most 100")
    private String email;

    @NotBlank(message = "password is required")
    private String password;
}
