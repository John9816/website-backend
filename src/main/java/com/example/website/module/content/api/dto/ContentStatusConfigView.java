package com.example.website.module.content.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ContentStatusConfigView {
    private String key;
    private String label;
    private boolean ready;
}
