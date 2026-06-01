package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.PageView;
import com.example.website.dto.kb.KbDocCreateRequest;
import com.example.website.dto.kb.KbDocMoveRequest;
import com.example.website.dto.kb.KbDocShareRequest;
import com.example.website.dto.kb.KbDocShareView;
import com.example.website.dto.kb.KbDocSummaryView;
import com.example.website.dto.kb.KbDocTagsRequest;
import com.example.website.dto.kb.KbDocUpdateRequest;
import com.example.website.dto.kb.KbDocVersionDetailView;
import com.example.website.dto.kb.KbDocVersionView;
import com.example.website.dto.kb.KbDocView;
import com.example.website.service.UserScopeService;
import com.example.website.service.kb.KbDocService;
import com.example.website.service.kb.KbDocShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping({"/api/admin/kb/docs", "/api/user/kb/docs"})
@RequiredArgsConstructor
public class KnowledgeDocController {

    private final KbDocService docService;
    private final KbDocShareService shareService;
    private final UserScopeService userScopeService;

    @GetMapping
    public ApiResponse<PageView<KbDocSummaryView>> search(HttpServletRequest request,
                                                          @RequestParam(required = false) Long spaceId,
                                                          @RequestParam(required = false) Long parentId,
                                                          @RequestParam(required = false) String keyword,
                                                          @RequestParam(required = false) Long tagId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(docService.search(userId, spaceId, parentId, keyword, tagId, page, size));
    }

    @PostMapping
    public ApiResponse<KbDocView> create(HttpServletRequest request,
                                         @Valid @RequestBody KbDocCreateRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(docService.create(userId, req));
    }

    @GetMapping("/{id}")
    public ApiResponse<KbDocView> get(HttpServletRequest request, @PathVariable Long id) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(docService.get(userId, id));
    }

    @PutMapping("/{id}")
    public ApiResponse<KbDocView> update(HttpServletRequest request, @PathVariable Long id,
                                         @Valid @RequestBody KbDocUpdateRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(docService.update(userId, id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        docService.delete(userId, id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/move")
    public ApiResponse<KbDocView> move(HttpServletRequest request, @PathVariable Long id,
                                       @Valid @RequestBody KbDocMoveRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(docService.move(userId, id, req));
    }

    @PutMapping("/{id}/tags")
    public ApiResponse<KbDocView> setTags(HttpServletRequest request, @PathVariable Long id,
                                          @RequestBody KbDocTagsRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(docService.replaceTags(userId, id, req.getTagIds()));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<PageView<KbDocVersionView>> listVersions(HttpServletRequest request,
                                                                @PathVariable Long id,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(docService.listVersions(userId, id, page, size));
    }

    @GetMapping("/{id}/versions/{versionId}")
    public ApiResponse<KbDocVersionDetailView> getVersion(HttpServletRequest request,
                                                          @PathVariable Long id,
                                                          @PathVariable Long versionId) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(docService.getVersion(userId, id, versionId));
    }

    @PostMapping("/{id}/versions/{versionId}/restore")
    public ApiResponse<KbDocView> restoreVersion(HttpServletRequest request,
                                                 @PathVariable Long id,
                                                 @PathVariable Long versionId) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(docService.restoreVersion(userId, id, versionId));
    }

    @GetMapping("/{id}/share")
    public ApiResponse<KbDocShareView> getShare(HttpServletRequest request, @PathVariable Long id) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(shareService.get(userId, id));
    }

    @PostMapping("/{id}/share")
    public ApiResponse<KbDocShareView> enableShare(HttpServletRequest request, @PathVariable Long id,
                                                   @RequestBody(required = false) KbDocShareRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(shareService.enable(userId, id, req));
    }

    @DeleteMapping("/{id}/share")
    public ApiResponse<Void> disableShare(HttpServletRequest request, @PathVariable Long id) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        shareService.disable(userId, id);
        return ApiResponse.ok();
    }
}
