package com.example.website.module.administration.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.module.identity.api.dto.AdminUserCreateRequest;
import com.example.website.module.identity.api.dto.AdminUserDetailView;
import com.example.website.module.identity.api.dto.AdminUserPasswordRequest;
import com.example.website.module.identity.api.dto.AdminUserUpdateRequest;
import com.example.website.module.identity.api.dto.AdminUserView;
import com.example.website.shared.dto.PageView;
import com.example.website.module.administration.application.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping("/{id}")
    public ApiResponse<AdminUserDetailView> detail(@PathVariable Long id) {
        return ApiResponse.ok(adminUserService.detail(id));
    }

    @GetMapping
    public ApiResponse<PageView<AdminUserView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminUserService.list(keyword, role, enabled, page, size));
    }

    @PostMapping
    public ApiResponse<AdminUserView> create(@Valid @RequestBody AdminUserCreateRequest request) {
        return ApiResponse.ok(adminUserService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminUserView> update(HttpServletRequest servletRequest,
                                              @PathVariable Long id,
                                              @Valid @RequestBody AdminUserUpdateRequest request) {
        Long actorId = (Long) servletRequest.getAttribute("userId");
        return ApiResponse.ok(adminUserService.update(actorId, id, request));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                           @Valid @RequestBody AdminUserPasswordRequest request) {
        adminUserService.resetPassword(id, request.getPassword());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest servletRequest, @PathVariable Long id) {
        Long actorId = (Long) servletRequest.getAttribute("userId");
        adminUserService.delete(actorId, id);
        return ApiResponse.ok();
    }
}
