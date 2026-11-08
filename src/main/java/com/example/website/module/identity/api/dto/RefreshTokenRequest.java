package com.example.website.module.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class RefreshTokenRequest {

    /**
     * Accept both snake_case (Android) and camelCase bodies.
     */
    @NotBlank
    @JsonProperty("refresh_token")
    @JsonAlias({"refreshToken", "refresh"})
    private String refreshToken;
}
