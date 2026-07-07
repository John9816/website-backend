package com.example.website.dto.content;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ContentStatusConfigView {
    private String key;
    private String label;
    private boolean ready;
}
