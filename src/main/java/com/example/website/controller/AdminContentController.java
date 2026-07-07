package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.PageView;
import com.example.website.dto.content.ContentArticleGenerateRequest;
import com.example.website.dto.content.ContentArticleUpdateRequest;
import com.example.website.dto.content.ContentArticleView;
import com.example.website.dto.content.ContentAutomationView;
import com.example.website.dto.content.ContentFactoryStatusView;
import com.example.website.dto.content.ContentHotTopicsView;
import com.example.website.service.ContentArticleService;
import com.example.website.service.UserScopeService;
import lombok.Data;
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
import java.util.Map;

@RestController
@RequestMapping("/api/admin/content")
@RequiredArgsConstructor
public class AdminContentController {

    private final ContentArticleService contentArticleService;
    private final UserScopeService userScopeService;

    @GetMapping("/status")
    public ApiResponse<ContentFactoryStatusView> status() {
        return ApiResponse.ok(contentArticleService.status());
    }

    @GetMapping("/hot")
    public ApiResponse<ContentHotTopicsView> hot(@RequestParam(defaultValue = "12") int limit,
                                                 @RequestParam(required = false) String category) {
        return ApiResponse.ok(contentArticleService.hotTopics(limit, category));
    }

    @GetMapping("/articles")
    public ApiResponse<PageView<ContentArticleView>> articles(HttpServletRequest request,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(contentArticleService.list(currentUserId(request), page, size));
    }

    @PostMapping("/articles/generate")
    public ApiResponse<ContentArticleView> generate(HttpServletRequest request,
                                                    @RequestBody(required = false) ContentArticleGenerateRequest body) {
        return ApiResponse.ok(contentArticleService.generate(currentUserId(request), body));
    }

    @GetMapping("/articles/{id}")
    public ApiResponse<ContentArticleView> article(HttpServletRequest request, @PathVariable Long id) {
        return ApiResponse.ok(contentArticleService.get(currentUserId(request), id));
    }

    @PutMapping("/articles/{id}")
    public ApiResponse<ContentArticleView> update(HttpServletRequest request,
                                                  @PathVariable Long id,
                                                  @RequestBody ContentArticleUpdateRequest body) {
        return ApiResponse.ok(contentArticleService.update(currentUserId(request), id, body));
    }

    @DeleteMapping("/articles/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        contentArticleService.delete(currentUserId(request), id);
        return ApiResponse.ok();
    }

    @PostMapping("/articles/{id}/wechat-draft")
    public ApiResponse<Map<String, Object>> createWechatDraft(HttpServletRequest request, @PathVariable Long id) {
        return ApiResponse.ok(contentArticleService.createWechatDraft(currentUserId(request), id));
    }

    @PostMapping("/articles/{id}/publish")
    public ApiResponse<Map<String, Object>> publish(HttpServletRequest request, @PathVariable Long id) {
        return ApiResponse.ok(contentArticleService.publishWechat(currentUserId(request), id));
    }

    @GetMapping("/automation")
    public ApiResponse<ContentAutomationView> automation(HttpServletRequest request,
                                                         @RequestParam(required = false) Long articleId) {
        return ApiResponse.ok(contentArticleService.automation(currentUserId(request), articleId));
    }

    @PostMapping("/automation/jobs/retry")
    public ApiResponse<ContentAutomationView> retryAutomationJob(HttpServletRequest request,
                                                                 @RequestBody RetryJobRequest body) {
        return ApiResponse.ok(contentArticleService.retryAutomationJob(currentUserId(request), body == null ? null : body.getJobId()));
    }

    private Long currentUserId(HttpServletRequest request) {
        return userScopeService.requireAuthenticatedUserId(request);
    }

    @Data
    public static class RetryJobRequest {
        private String jobId;
    }
}
