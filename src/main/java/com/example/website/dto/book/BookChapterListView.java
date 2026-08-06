package com.example.website.dto.book;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class BookChapterListView {
    private List<BookChapterView> list;
    private int total;
}
