package com.example.website.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Data
@AllArgsConstructor
public class PageView<T> {

    private List<T> items;
    private long total;
    private int page;
    private int size;

    public static <S, T> PageView<T> from(Page<S> page, Function<S, T> mapper) {
        List<T> items = page.getContent().stream().map(mapper).collect(Collectors.toList());
        return new PageView<>(items, page.getTotalElements(), page.getNumber(), page.getSize());
    }
}
