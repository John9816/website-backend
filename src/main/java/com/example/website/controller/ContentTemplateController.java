package com.example.website.controller;

import com.example.website.service.content.ContentTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/content/templates")
@RequiredArgsConstructor
public class ContentTemplateController {

    private final ContentTemplateService templateService;

    /**
     * 创建模板
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createTemplate(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(templateService.createTemplate(userId, request));
    }

    /**
     * 更新模板
     */
    @PutMapping("/{templateId}")
    public ResponseEntity<Map<String, Object>> updateTemplate(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long templateId,
            @RequestBody Map<String, Object> request) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(templateService.updateTemplate(userId, templateId, request));
    }

    /**
     * 删除模板
     */
    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> deleteTemplate(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long templateId) {
        Long userId = Long.parseLong(userDetails.getUsername());
        templateService.deleteTemplate(userId, templateId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 获取模板详情
     */
    @GetMapping("/{templateId}")
    public ResponseEntity<Map<String, Object>> getTemplate(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long templateId) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(templateService.getTemplate(userId, templateId));
    }

    /**
     * 获取模板列表
     */
    @GetMapping
    public ResponseEntity<Page<Map<String, Object>>> getTemplates(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) Boolean onlyActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = Long.parseLong(userDetails.getUsername());
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(templateService.getTemplates(userId, onlyActive, pageable));
    }

    /**
     * 按分类获取模板
     */
    @GetMapping("/by-category")
    public ResponseEntity<List<Map<String, Object>>> getTemplatesByCategory(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam String category) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(templateService.getTemplatesByCategory(userId, category));
    }

    /**
     * 搜索模板
     */
    @GetMapping("/search")
    public ResponseEntity<Page<Map<String, Object>>> searchTemplates(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = Long.parseLong(userDetails.getUsername());
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(templateService.searchTemplates(userId, keyword, pageable));
    }

    /**
     * 获取所有分类
     */
    @GetMapping("/categories")
    public ResponseEntity<List<String>> getCategories(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(templateService.getCategories(userId));
    }

    /**
     * 复制模板
     */
    @PostMapping("/{templateId}/duplicate")
    public ResponseEntity<Map<String, Object>> duplicateTemplate(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long templateId) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(templateService.duplicateTemplate(userId, templateId));
    }

    /**
     * 获取统计信息
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(templateService.getStatistics(userId));
    }
}
