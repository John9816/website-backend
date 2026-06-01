package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.CategoryRequest;
import com.example.website.dto.CategoryView;
import com.example.website.service.CategoryService;
import com.example.website.service.UserScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping({"/api/admin/categories", "/api/user/categories"})
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryService categoryService;
    private final UserScopeService userScopeService;

    @GetMapping
    public ApiResponse<List<CategoryView>> list(HttpServletRequest request) {
        return ApiResponse.ok(categoryService.listAll(userScopeService.requireAuthenticatedUserId(request)));
    }

    @GetMapping("/{id}")
    public ApiResponse<CategoryView> get(HttpServletRequest request, @PathVariable Long id) {
        return ApiResponse.ok(categoryService.get(userScopeService.requireAuthenticatedUserId(request), id));
    }

    @PostMapping
    public ApiResponse<CategoryView> create(HttpServletRequest request, @Valid @RequestBody CategoryRequest req) {
        return ApiResponse.ok(categoryService.create(userScopeService.requireAuthenticatedUserId(request), req));
    }

    @PutMapping("/{id}")
    public ApiResponse<CategoryView> update(HttpServletRequest request, @PathVariable Long id,
                                            @Valid @RequestBody CategoryRequest req) {
        return ApiResponse.ok(categoryService.update(userScopeService.requireAuthenticatedUserId(request), id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        categoryService.delete(userScopeService.requireAuthenticatedUserId(request), id);
        return ApiResponse.ok();
    }
}
