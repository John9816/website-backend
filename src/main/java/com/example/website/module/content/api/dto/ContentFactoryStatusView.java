package com.example.website.module.content.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ContentFactoryStatusView {
    private boolean aiReady;
    private boolean imageReady;
    private boolean wechatReady;
    private List<ContentStatusConfigView> configs;
}
