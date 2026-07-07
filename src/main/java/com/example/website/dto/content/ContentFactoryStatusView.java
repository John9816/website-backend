package com.example.website.dto.content;

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
