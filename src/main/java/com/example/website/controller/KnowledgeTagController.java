package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.kb.KbTagRequest;
import com.example.website.dto.kb.KbTagView;
import com.example.website.service.UserScopeService;
import com.example.website.service.kb.KbTagService;
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
@RequestMapping({"/api/admin/kb/tags", "/api/user/kb/tags"})
@RequiredArgsConstructor
public class KnowledgeTagController {

    private final KbTagService tagService;
    private final UserScopeService userScopeService;

    @GetMapping
    public ApiResponse<List<KbTagView>> list(HttpServletRequest request) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(tagService.list(userId));
    }

    @PostMapping
    public ApiResponse<KbTagView> create(HttpServletRequest request, @Valid @RequestBody KbTagRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(tagService.create(userId, req));
    }

    @PutMapping("/{id}")
    public ApiResponse<KbTagView> update(HttpServletRequest request, @PathVariable Long id,
                                         @Valid @RequestBody KbTagRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(tagService.update(userId, id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        tagService.delete(userId, id);
        return ApiResponse.ok();
    }
}
