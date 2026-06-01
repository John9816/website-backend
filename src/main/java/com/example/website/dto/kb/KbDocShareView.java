package com.example.website.dto.kb;

import com.example.website.entity.KbDocShare;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KbDocShareView {
    private Long docId;
    private String token;
    private Boolean enabled;
    private LocalDateTime expiresAt;
    private Integer viewCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static KbDocShareView from(KbDocShare s) {
        if (s == null) return null;
        KbDocShareView v = new KbDocShareView();
        v.docId = s.getDocId();
        v.token = s.getToken();
        v.enabled = s.getEnabled();
        v.expiresAt = s.getExpiresAt();
        v.viewCount = s.getViewCount();
        v.createdAt = s.getCreatedAt();
        v.updatedAt = s.getUpdatedAt();
        return v;
    }
}
