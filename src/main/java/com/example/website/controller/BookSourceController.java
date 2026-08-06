package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.book.BookChapterListView;
import com.example.website.dto.book.BookContentView;
import com.example.website.dto.book.BookSearchPageView;
import com.example.website.dto.book.BookSourceCatalogView;
import com.example.website.dto.book.BookView;
import com.example.website.service.BookSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/books")
@RequiredArgsConstructor
public class BookSourceController {

    private final BookSourceService bookSourceService;

    @GetMapping("/sources")
    public ApiResponse<List<BookSourceCatalogView>> sources() {
        return ApiResponse.ok(bookSourceService.listCatalogs());
    }

    @GetMapping("/search")
    public ApiResponse<BookSearchPageView> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sourceId) {
        return ApiResponse.ok(bookSourceService.search(sourceId, keyword, page, pageSize));
    }

    @GetMapping("/detail")
    public ApiResponse<BookView> detail(
            @RequestParam(required = false) String sourceId,
            @RequestParam String bookUrl) {
        return ApiResponse.ok(bookSourceService.detail(sourceId, bookUrl));
    }

    @GetMapping("/chapters")
    public ApiResponse<BookChapterListView> chapters(
            @RequestParam(required = false) String sourceId,
            @RequestParam String bookUrl) {
        return ApiResponse.ok(bookSourceService.chapters(sourceId, bookUrl));
    }

    @GetMapping("/content")
    public ApiResponse<BookContentView> content(
            @RequestParam(required = false) String sourceId,
            @RequestParam String bookUrl,
            @RequestParam String chapterUrl) {
        return ApiResponse.ok(bookSourceService.content(sourceId, bookUrl, chapterUrl));
    }
}
