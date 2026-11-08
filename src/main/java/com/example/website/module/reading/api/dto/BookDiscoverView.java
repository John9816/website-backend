package com.example.website.module.reading.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class BookDiscoverView {
    private String sourceId;
    private String sourceName;
    private List<BookCategoryView> categories;
    private List<BookDiscoverSectionView> sections;
}
