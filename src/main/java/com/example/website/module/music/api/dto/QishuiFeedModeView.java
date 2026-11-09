package com.example.website.module.music.api.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QishuiFeedModeView {
    private String id;
    private String name;
    private String group;
    private String iconUrl;
    private String toast;
}
