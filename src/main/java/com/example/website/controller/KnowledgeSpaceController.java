package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.kb.KbDocTreeNodeView;
import com.example.website.dto.kb.KbSpaceRequest;
import com.example.website.dto.kb.KbSpaceView;
import com.example.website.service.UserScopeService;
import com.example.website.service.kb.KbDocService;
import com.example.website.service.kb.KbSpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping({"/api/admin/kb/spaces", "/api/user/kb/spaces"})
@RequiredArgsConstructor
public class KnowledgeSpaceController {

    private final KbSpaceService spaceService;
    private final KbDocService docService;
    private final UserScopeService userScopeService;

    @GetMapping
    public ApiResponse<List<KbSpaceView>> list(HttpServletRequest request) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(spaceService.list(userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<KbSpaceView> get(HttpServletRequest request, @PathVariable Long id) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(spaceService.get(userId, id));
    }

    @PostMapping
    public ApiResponse<KbSpaceView> create(HttpServletRequest request, @Valid @RequestBody KbSpaceRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(spaceService.create(userId, req));
    }

    @PutMapping("/{id}")
    public ApiResponse<KbSpaceView> update(HttpServletRequest request, @PathVariable Long id,
                                           @Valid @RequestBody KbSpaceRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(spaceService.update(userId, id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        spaceService.delete(userId, id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/tree")
    public ApiResponse<List<KbDocTreeNodeView>> tree(HttpServletRequest request, @PathVariable Long id) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(docService.tree(userId, id));
    }
}
