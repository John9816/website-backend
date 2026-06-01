package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.LinkRequest;
import com.example.website.dto.LinkView;
import com.example.website.service.LinkService;
import com.example.website.service.UserScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping({"/api/admin/links", "/api/user/links"})
@RequiredArgsConstructor
public class AdminLinkController {

    private final LinkService linkService;
    private final UserScopeService userScopeService;

    @GetMapping
    public ApiResponse<List<LinkView>> list(HttpServletRequest request,
                                            @RequestParam(required = false) Long categoryId) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(categoryId == null
                ? linkService.listAll(userId)
                : linkService.listByCategory(userId, categoryId));
    }

    @GetMapping("/{id}")
    public ApiResponse<LinkView> get(HttpServletRequest request, @PathVariable Long id) {
        return ApiResponse.ok(linkService.get(userScopeService.requireAuthenticatedUserId(request), id));
    }

    @PostMapping
    public ApiResponse<LinkView> create(HttpServletRequest request, @Valid @RequestBody LinkRequest req) {
        return ApiResponse.ok(linkService.create(userScopeService.requireAuthenticatedUserId(request), req));
    }

    @PutMapping("/{id}")
    public ApiResponse<LinkView> update(HttpServletRequest request, @PathVariable Long id,
                                        @Valid @RequestBody LinkRequest req) {
        return ApiResponse.ok(linkService.update(userScopeService.requireAuthenticatedUserId(request), id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        linkService.delete(userScopeService.requireAuthenticatedUserId(request), id);
        return ApiResponse.ok();
    }
}
