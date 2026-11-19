package com.example.website.module.settings.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.module.settings.application.SysConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public UI configuration consumed by every visitor (including anonymous ones)
 * to decide which navigation tabs are visible. Values come from the generic
 * sys_config table under a well-known key; default (missing/invalid) means all
 * tabs visible, which is handled on the client side.
 */
@RestController
@RequestMapping("/api/public/ui")
@RequiredArgsConstructor
public class PublicUiConfigController {

    public static final String TAB_VISIBILITY_KEY = "ui.tabs.visibility";

    private static final TypeReference<Map<String, Boolean>> MAP_TYPE =
            new TypeReference<Map<String, Boolean>>() {};

    private final SysConfigService configService;
    private final ObjectMapper objectMapper;

    @GetMapping("/tabs")
    public ApiResponse<Map<String, Boolean>> tabs() {
        String raw = configService.getValue(TAB_VISIBILITY_KEY).orElse(null);
        if (raw == null || raw.isBlank()) {
            return ApiResponse.ok(Collections.emptyMap());
        }
        try {
            Map<String, Boolean> parsed = objectMapper.readValue(raw, MAP_TYPE);
            if (parsed == null) return ApiResponse.ok(Collections.emptyMap());
            Map<String, Boolean> result = new LinkedHashMap<>();
            parsed.forEach((k, v) -> result.put(k, Boolean.TRUE.equals(v)));
            return ApiResponse.ok(result);
        } catch (Exception e) {
            // Malformed admin input must never break the site navigation.
            return ApiResponse.ok(Collections.emptyMap());
        }
    }
}
