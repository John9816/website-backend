package com.example.website.module.reading.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class BookSearchPageView {
    private List<BookView> list;
    private int page;
    private int pageSize;
    private int total;
    private boolean hasMore;
}
