package com.example.website.dto.book;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class BookDiscoverSectionView {
    private String key;
    private String title;
    private List<BookView> books;
}
