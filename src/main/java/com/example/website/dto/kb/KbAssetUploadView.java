package com.example.website.dto.kb;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class KbAssetUploadView {
    private String url;
    private String key;
    private String contentType;
    private long size;
}
