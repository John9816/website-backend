package com.example.website.dto.book;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** The stable book shape consumed by the Flutter reader. */
@Getter
@AllArgsConstructor
public class BookView {
    private String sourceId;
    private String sourceName;
    private String name;
    private String author;
    private String coverUrl;
    private String bookUrl;
    private String intro;
    private String latestChapter;
    private String status;
    private String category;
}
