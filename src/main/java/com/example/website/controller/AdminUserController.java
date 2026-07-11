package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.AdminUserCreateRequest;
import com.example.website.dto.AdminUserPasswordRequest;
import com.example.website.dto.AdminUserUpdateRequest;
import com.example.website.dto.AdminUserView;
import com.example.website.dto.PageView;
import com.example.website.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

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
}
