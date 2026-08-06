package com.example.website.dto.book;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BookUserSourceView {
    private Long id;
    private String sourceId;
    private String sourceName;
    private String sourceUrl;
    private String sourceType;
    private boolean enabled;
    private String status;
    private String message;
}
