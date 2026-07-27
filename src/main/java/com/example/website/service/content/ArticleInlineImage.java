package com.example.website.service.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleInlineImage {
    private String slotId;
    private String sectionKey;
    private String sectionTitle;
    private String prompt;
    private String alt;
    private String sourceType;
    private String sourceUrl;
    private String sourcePageUrl;
    private String author;
    private String license;
    private String attribution;
    private String mimeType;
    private Integer width;
    private Integer height;
    private Long byteSize;
    private String sha256;
    private String wechatUrl;
    private String status;
    private String failureReason;

    public boolean ready() {
        return "READY".equals(status)
                && ContentTextUtils.hasText(sourceUrl)
                && ContentTextUtils.hasText(sectionTitle)
                && ContentTextUtils.hasText(alt)
                && ContentTextUtils.hasText(sha256);
    }

    public String renderUrl() {
        return ContentTextUtils.hasText(wechatUrl) ? wechatUrl : sourceUrl;
    }
}
