package com.example.website.dto.music;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Deliberately separate from the encrypted credential model. */
@Getter
@Setter
public class QishuiAccountView {
    private boolean bound;
    private String status;
    private String sessionId;
    private String qrcode;
    private String qrcodeUrl;
    private Instant expiresAt;
    private Instant sessionExpiresAt;
    private String nickname;
    private String avatarUrl;
    private Boolean vip;
    private Integer pollIntervalSeconds;
}
