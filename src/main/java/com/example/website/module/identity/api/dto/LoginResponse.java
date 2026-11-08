package com.example.website.module.identity.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private String tokenType;
    private long expiresInMinutes;
    /**
     * Opaque refresh token for session renewal.
     * Android AuthResponseParser accepts refreshToken / refresh_token.
     */
    private String refreshToken;
    private String username;
    private String role;
}
