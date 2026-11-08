package com.example.website.module.settings.api.dto;

import com.example.website.module.settings.domain.SysConfig;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SysConfigView {

    private Long id;
    private String configKey;
    private String configValue;
    private boolean sensitive;
    private boolean configured;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static SysConfigView from(SysConfig c) {
        SysConfigView v = new SysConfigView();
        v.id = c.getId();
        v.configKey = c.getConfigKey();
        v.sensitive = isSensitiveKey(c.getConfigKey());
        v.configured = c.getConfigValue() != null && !c.getConfigValue().trim().isEmpty();
        v.configValue = v.sensitive ? null : c.getConfigValue();
        v.description = c.getDescription();
        v.createdAt = c.getCreatedAt();
        v.updatedAt = c.getUpdatedAt();
        return v;
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(java.util.Locale.ROOT)
                .replace("_", "")
                .replace("-", "");
        return normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("apikey")
                || normalized.contains("credential")
                || normalized.endsWith(".key");
    }
}
