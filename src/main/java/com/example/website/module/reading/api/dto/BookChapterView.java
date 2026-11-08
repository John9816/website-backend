package com.example.website.module.reading.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Getter
@AllArgsConstructor
public class BookChapterView {
    private String title;
    private String url;
    private int index;
    private String volume;

    /** Compatibility accessor for callers that use the Flutter model name. */
    @JsonIgnore
    public String getChapterUrl() {
        return url;
    }
}
