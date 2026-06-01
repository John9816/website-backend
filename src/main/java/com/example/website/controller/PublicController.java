package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.CategoryView;
import com.example.website.dto.LinkView;
import com.example.website.service.CategoryService;
import com.example.website.service.LinkService;
import com.example.website.service.UserScopeService;
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
}
