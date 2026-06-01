package com.example.website.dto;

import com.example.website.entity.SysConfig;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SysConfigView {

    private Long id;
    private String configKey;
    private String configValue;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static SysConfigView from(SysConfig c) {
        SysConfigView v = new SysConfigView();
        v.id = c.getId();
        v.configKey = c.getConfigKey();
        v.configValue = c.getConfigValue();
        v.description = c.getDescription();
        v.createdAt = c.getCreatedAt();
        v.updatedAt = c.getUpdatedAt();
        return v;
    }
}
