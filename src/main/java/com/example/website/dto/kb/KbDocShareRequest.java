package com.example.website.dto.kb;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KbDocShareRequest {

    private Boolean enabled;

    private LocalDateTime expiresAt;

    private Boolean rotateToken;
}
