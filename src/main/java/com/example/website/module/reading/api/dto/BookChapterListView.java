package com.example.website.module.reading.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class BookChapterListView {
    private List<BookChapterView> list;
    private int total;
}
