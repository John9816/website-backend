package com.example.website.module.music.application;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Internal account material. Deliberately has no generated toString or public API binding. */
@Getter
@Setter
public class QishuiCredential {
    private String privateScalar;
    private String deviceId;
    private String ticket;
    private String guardTsSign;
    private String guardCert;
    private String accessCode;
    private String openId;

    /** The upstream account ID, never the website account ID. */
    private String userId;

    private Instant expiresAt;
    private Profile profile;

    @Getter
    @Setter
    public static class Profile {
        private String nickname;
        private String avatarUrl;
        private Boolean isVip;
    }
}
