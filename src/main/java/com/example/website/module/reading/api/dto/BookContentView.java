package com.example.website.module.reading.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Getter
@AllArgsConstructor
public class BookContentView {
    private String title;
    private String content;
    private String chapterUrl;

    /** Compatibility accessor for server-side callers and older clients. */
    @JsonIgnore
    public String getText() {
        return content;
    }
}
