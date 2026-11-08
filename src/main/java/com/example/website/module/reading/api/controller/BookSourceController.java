package com.example.website.module.reading.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.module.reading.api.dto.BookChapterListView;
import com.example.website.module.reading.api.dto.BookContentView;
import com.example.website.module.reading.api.dto.BookDiscoverView;
import com.example.website.module.reading.api.dto.BookSearchPageView;
import com.example.website.module.reading.api.dto.BookSourceCatalogView;
import com.example.website.module.reading.api.dto.BookView;
import com.example.website.module.reading.application.BookSourceService;
import com.example.website.module.reading.application.ImportedBookSourceReader;
import com.example.website.module.identity.application.UserScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.servlet.http.HttpServletRequest;

import java.util.List;

@RestController
@RequestMapping("/api/v1/books")
@RequiredArgsConstructor
public class BookSourceController {

    private final BookSourceService bookSourceService;
    private final ImportedBookSourceReader importedReader;
    private final UserScopeService userScopeService;

    @GetMapping("/sources")
    public ApiResponse<List<BookSourceCatalogView>> sources() {
        return ApiResponse.ok(bookSourceService.listCatalogs());
    }

    @GetMapping("/search")
    public ApiResponse<BookSearchPageView> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sourceId,
            HttpServletRequest request) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        if (importedReader.supports(userId, sourceId)) return ApiResponse.ok(importedReader.search(userId, sourceId, keyword, page, pageSize));
        return ApiResponse.ok(bookSourceService.search(sourceId, keyword, page, pageSize));
    }

    @GetMapping("/discover")
    public ApiResponse<BookDiscoverView> discover(
            @RequestParam(required = false) String sourceId,
            @RequestParam(defaultValue = "home") String section,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page) {
        return ApiResponse.ok(bookSourceService.discover(sourceId, section, category, page));
    }

    @GetMapping("/detail")
    public ApiResponse<BookView> detail(
            @RequestParam(required = false) String sourceId,
            @RequestParam String bookUrl,
            HttpServletRequest request) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        if (importedReader.supports(userId, sourceId)) return ApiResponse.ok(importedReader.detail(userId, sourceId, bookUrl));
        return ApiResponse.ok(bookSourceService.detail(sourceId, bookUrl));
    }

    @GetMapping("/chapters")
    public ApiResponse<BookChapterListView> chapters(
            @RequestParam(required = false) String sourceId,
            @RequestParam String bookUrl,
            HttpServletRequest request) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        if (importedReader.supports(userId, sourceId)) return ApiResponse.ok(importedReader.chapters(userId, sourceId, bookUrl));
        return ApiResponse.ok(bookSourceService.chapters(sourceId, bookUrl));
    }

    @GetMapping("/content")
    public ApiResponse<BookContentView> content(
            @RequestParam(required = false) String sourceId,
            @RequestParam String bookUrl,
            @RequestParam String chapterUrl,
            HttpServletRequest request) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        if (importedReader.supports(userId, sourceId)) return ApiResponse.ok(importedReader.content(userId, sourceId, bookUrl, chapterUrl));
        return ApiResponse.ok(bookSourceService.content(sourceId, bookUrl, chapterUrl));
    }
}
