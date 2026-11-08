package com.example.website.module.content.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.shared.dto.PageView;
import com.example.website.module.content.api.dto.ContentAgentRunJobView;
import com.example.website.module.content.api.dto.ContentAgentRunRequest;
import com.example.website.module.content.api.dto.ContentArticleGenerateRequest;
import com.example.website.module.content.api.dto.ContentArticleUpdateRequest;
import com.example.website.module.content.api.dto.ContentArticleView;
import com.example.website.module.content.api.dto.ContentAutomationView;
import com.example.website.module.content.api.dto.ContentFactoryStatusView;
import com.example.website.module.content.api.dto.ContentHotTopicsView;
import com.example.website.module.content.application.ContentAgentJobService;
import com.example.website.module.content.application.ContentArticleService;
import com.example.website.module.content.application.ContentAutopilotService;
import com.example.website.module.identity.application.UserScopeService;
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
    private final ContentAgentJobService contentAgentJobService;
    private final ContentAutopilotService contentAutopilotService;
    private final UserScopeService userScopeService;

    @GetMapping("/status")
    public ApiResponse<ContentFactoryStatusView> status() {
        return ApiResponse.ok(contentArticleService.status());
    }

    @GetMapping("/autopilot")
    public ApiResponse<Map<String, Object>> autopilot(HttpServletRequest request) {
        return ApiResponse.ok(contentAutopilotService.settings(currentUserId(request)));
    }

    @PutMapping("/autopilot")
    public ApiResponse<Map<String, Object>> updateAutopilot(HttpServletRequest request,
                                                             @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(contentAutopilotService.updateSettings(currentUserId(request), body));
    }

    @PostMapping("/autopilot/run")
    public ApiResponse<ContentAgentRunJobView> runAutopilot(HttpServletRequest request) {
        return ApiResponse.ok(contentAutopilotService.runNow(currentUserId(request)));
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
        return ApiResponse.ok(contentArticleService.listForAdmin(page, size));
    }

    @PostMapping("/articles/generate")
    public ApiResponse<ContentArticleView> generate(HttpServletRequest request,
                                                    @RequestBody(required = false) ContentArticleGenerateRequest body) {
        // Synchronous path kept for compatibility; UI should prefer generate-async.
        return ApiResponse.ok(contentArticleService.generate(currentUserId(request), body));
    }

    /**
     * Async generate: returns a job immediately so long pipelines do not hit proxy/HTTP timeouts.
     * Poll {@code GET /automation/run/{jobId}} for result.
     */
    @PostMapping("/articles/generate-async")
    public ApiResponse<ContentAgentRunJobView> generateAsync(HttpServletRequest request,
                                                             @RequestBody(required = false) ContentArticleGenerateRequest body) {
        return ApiResponse.ok(contentAgentJobService.startGenerate(currentUserId(request), body));
    }

    @GetMapping("/articles/{id}")
    public ApiResponse<ContentArticleView> article(HttpServletRequest request, @PathVariable Long id) {
        return ApiResponse.ok(contentArticleService.getForAdmin(id));
    }

    @PutMapping("/articles/{id}")
    public ApiResponse<ContentArticleView> update(HttpServletRequest request,
                                                  @PathVariable Long id,
                                                  @RequestBody ContentArticleUpdateRequest body) {
        return ApiResponse.ok(contentArticleService.updateForAdmin(id, body));
    }

    /**
     * Directed rewrite from current markdown + stored review/evidence (does not re-pick topic).
     */
    @PostMapping("/articles/{id}/revise")
    public ApiResponse<ContentArticleView> revise(HttpServletRequest request, @PathVariable Long id) {
        return ApiResponse.ok(contentArticleService.reviseForAdmin(id));
    }

    /**
     * Re-generate cover + inline images only, then re-evaluate delivery gate.
     */
    @PostMapping("/articles/{id}/visuals")
    public ApiResponse<ContentArticleView> refreshVisuals(HttpServletRequest request, @PathVariable Long id) {
        return ApiResponse.ok(contentArticleService.refreshVisualsForAdmin(id));
    }

    @DeleteMapping("/articles/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        contentArticleService.deleteForAdmin(id);
        return ApiResponse.ok();
    }

    @PostMapping("/articles/{id}/wechat-draft")
    public ApiResponse<Map<String, Object>> createWechatDraft(HttpServletRequest request, @PathVariable Long id) {
        return ApiResponse.ok(contentArticleService.createWechatDraftForAdmin(id));
    }

    @PostMapping("/articles/{id}/publish")
    public ApiResponse<Map<String, Object>> publish(HttpServletRequest request, @PathVariable Long id) {
        return ApiResponse.ok(contentArticleService.publishWechatForAdmin(id));
    }

    @GetMapping("/automation")
    public ApiResponse<ContentAutomationView> automation(HttpServletRequest request,
                                                         @RequestParam(required = false) Long articleId) {
        return ApiResponse.ok(contentArticleService.automationForAdmin(articleId));
    }

    @PostMapping("/automation/run")
    public ApiResponse<ContentAgentRunJobView> runAutomationAgent(HttpServletRequest request,
                                                                  @RequestBody(required = false) ContentAgentRunRequest body) {
        return ApiResponse.ok(contentAgentJobService.start(currentUserId(request), body));
    }

    @GetMapping("/automation/run/{jobId}")
    public ApiResponse<ContentAgentRunJobView> automationAgentJob(HttpServletRequest request,
                                                                  @PathVariable String jobId) {
        ContentAgentRunJobView job = contentAgentJobService.get(currentUserId(request), jobId);
        return job == null ? ApiResponse.error(404, "Content agent job not found") : ApiResponse.ok(job);
    }

    @PostMapping("/automation/jobs/retry")
    public ApiResponse<ContentAutomationView> retryAutomationJob(HttpServletRequest request,
                                                                 @RequestBody RetryJobRequest body) {
        return ApiResponse.ok(contentArticleService.retryAutomationJobForAdmin(body == null ? null : body.getJobId()));
    }

    private Long currentUserId(HttpServletRequest request) {
        return userScopeService.requireAuthenticatedUserId(request);
    }

    @Data
    public static class RetryJobRequest {
        private String jobId;
    }
}
