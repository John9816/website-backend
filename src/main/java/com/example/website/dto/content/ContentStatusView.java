package com.example.website.dto.content;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ContentStatusView {
    private boolean aiReady;
    private boolean imageReady;
    private boolean wechatReady;
    private List<ConfigStatus> configs;

    @Data
    @AllArgsConstructor
    public static class ConfigStatus {
        private String key;
        private String label;
        private boolean ready;
    }
}
