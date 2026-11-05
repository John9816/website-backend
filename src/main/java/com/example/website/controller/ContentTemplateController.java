package com.example.website.controller;

import com.example.website.service.content.ContentTemplateService;
import com.example.website.service.UserScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/content/templates")
@RequiredArgsConstructor
public class ContentTemplateController {

    private final ContentTemplateService templateService;
    private final UserScopeService userScopeService;

    /**
     * 创建模板
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createTemplate(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(templateService.createTemplate(currentUserId(request), body));
    }

    /**
     * 更新模板
     */
    @PutMapping("/{templateId}")
    public ResponseEntity<Map<String, Object>> updateTemplate(
            HttpServletRequest request,
            @PathVariable Long templateId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(templateService.updateTemplate(currentUserId(request), templateId, body));
    }

    /**
     * 删除模板
     */
    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> deleteTemplate(
            HttpServletRequest request,
            @PathVariable Long templateId) {
        Long userId = currentUserId(request);
        templateService.deleteTemplate(userId, templateId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 获取模板详情
     */
    @GetMapping("/{templateId}")
    public ResponseEntity<Map<String, Object>> getTemplate(
            HttpServletRequest request,
            @PathVariable Long templateId) {
        Long userId = currentUserId(request);
        return ResponseEntity.ok(templateService.getTemplate(userId, templateId));
    }

    /**
     * 获取模板列表
     */
    @GetMapping
    public ResponseEntity<Page<Map<String, Object>>> getTemplates(
            HttpServletRequest request,
            @RequestParam(required = false) Boolean onlyActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = currentUserId(request);
        Pageable pageable = PageRequest.of(Math.max(0, page), boundedSize(size));
        return ResponseEntity.ok(templateService.getTemplates(userId, onlyActive, pageable));
    }

    /**
     * 按分类获取模板
     */
    @GetMapping("/by-category")
    public ResponseEntity<List<Map<String, Object>>> getTemplatesByCategory(
            HttpServletRequest request,
            @RequestParam String category) {
        Long userId = currentUserId(request);
        return ResponseEntity.ok(templateService.getTemplatesByCategory(userId, category));
    }

    /**
     * 搜索模板
     */
    @GetMapping("/search")
    public ResponseEntity<Page<Map<String, Object>>> searchTemplates(
            HttpServletRequest request,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = currentUserId(request);
        Pageable pageable = PageRequest.of(Math.max(0, page), boundedSize(size));
        return ResponseEntity.ok(templateService.searchTemplates(userId, keyword, pageable));
    }

    /**
     * 获取所有分类
     */
    @GetMapping("/categories")
    public ResponseEntity<List<String>> getCategories(
            HttpServletRequest request) {
        Long userId = currentUserId(request);
        return ResponseEntity.ok(templateService.getCategories(userId));
    }

    /**
     * 复制模板
     */
    @PostMapping("/{templateId}/duplicate")
    public ResponseEntity<Map<String, Object>> duplicateTemplate(
            HttpServletRequest request,
            @PathVariable Long templateId) {
        Long userId = currentUserId(request);
        return ResponseEntity.ok(templateService.duplicateTemplate(userId, templateId));
    }

    /**
     * 获取统计信息
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics(
            HttpServletRequest request) {
        Long userId = currentUserId(request);
        return ResponseEntity.ok(templateService.getStatistics(userId));
    }

    private Long currentUserId(HttpServletRequest request) {
        return userScopeService.requireAuthenticatedUserId(request);
    }

    private int boundedSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
