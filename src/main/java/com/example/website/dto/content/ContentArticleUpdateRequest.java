package com.example.website.dto.content;

import lombok.Data;

@Data
public class ContentArticleUpdateRequest {
    private String title;
    private String category;
    private String digest;
    private String contentMarkdown;
    private String contentHtml;
    private String coverImageUrl;
}
