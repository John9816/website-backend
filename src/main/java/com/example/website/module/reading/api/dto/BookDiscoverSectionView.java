package com.example.website.module.reading.api.dto;

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
