package com.example.website.module.identity.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class EmailVerificationCodeView {

    private final long resendAfterSeconds;
    private final long expiresInSeconds;
}
