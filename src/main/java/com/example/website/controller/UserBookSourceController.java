package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.book.BookSourceEnabledRequest;
import com.example.website.dto.book.BookSourceImportRequest;
import com.example.website.dto.book.BookUserSourceView;
import com.example.website.service.UserBookSourceService;
import com.example.website.service.UserScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/v1/books/user-sources")
@RequiredArgsConstructor
public class UserBookSourceController {
    private final UserBookSourceService service;
    private final UserScopeService userScopeService;

    @GetMapping
    public ApiResponse<List<BookUserSourceView>> list(HttpServletRequest request) {
        return ApiResponse.ok(service.list(userScopeService.requireAuthenticatedUserId(request)));
    }

    @PostMapping("/import")
    public ApiResponse<BookUserSourceView> importSource(HttpServletRequest request, @Valid @RequestBody BookSourceImportRequest body) {
        return ApiResponse.ok(service.importSource(userScopeService.requireAuthenticatedUserId(request), body));
    }

    @PatchMapping("/{id}/enabled")
    public ApiResponse<BookUserSourceView> enabled(HttpServletRequest request, @PathVariable Long id, @Valid @RequestBody BookSourceEnabledRequest body) {
        return ApiResponse.ok(service.setEnabled(userScopeService.requireAuthenticatedUserId(request), id, body));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        service.delete(userScopeService.requireAuthenticatedUserId(request), id);
        return ApiResponse.ok();
    }
}
