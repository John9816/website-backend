package com.example.website.module.navigation.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.config.PerformanceMetrics;
import com.example.website.module.navigation.api.dto.CategoryView;
import com.example.website.module.navigation.api.dto.LinkView;
import com.example.website.module.navigation.application.CategoryService;
import com.example.website.module.navigation.application.LinkService;
import com.example.website.module.identity.application.UserScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {

    private final CategoryService categoryService;
    private final LinkService linkService;
    private final UserScopeService userScopeService;
    private final PerformanceMetrics performanceMetrics;

    @GetMapping("/categories")
    public ApiResponse<List<CategoryView>> categories(HttpServletRequest request) {
        return ApiResponse.ok(categoryService.listAll(userScopeService.currentUserOrAdmin(request)));
    }

    @GetMapping("/nav")
    public ApiResponse<List<CategoryView>> nav(HttpServletRequest request) {
        return ApiResponse.ok(categoryService.listAllWithLinks(userScopeService.currentUserOrAdmin(request)));
    }

    @GetMapping("/links")
    public ApiResponse<List<LinkView>> links(HttpServletRequest request,
                                             @RequestParam(required = false) Long categoryId) {
        Long userId = userScopeService.currentUserOrAdmin(request);
        return ApiResponse.ok(categoryId == null
                ? linkService.listAll(userId)
                : linkService.listByCategory(userId, categoryId));
    }

    @GetMapping("/metrics")
    public ApiResponse<PerformanceMetrics.Snapshot> metrics() {
        return ApiResponse.ok(performanceMetrics.snapshot());
    }
}
