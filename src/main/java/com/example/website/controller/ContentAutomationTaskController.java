package com.example.website.controller;

import com.example.website.service.content.ContentAutomationTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/content/automation")
@RequiredArgsConstructor
public class ContentAutomationTaskController {

    private final ContentAutomationTaskService taskService;

    /**
     * 创建自动化任务
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createTask(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(taskService.createTask(userId, request));
    }

    /**
     * 更新自动化任务
     */
    @PutMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> updateTask(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long taskId,
            @RequestBody Map<String, Object> request) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(taskService.updateTask(userId, taskId, request));
    }

    /**
     * 删除自动化任务
     */
    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> deleteTask(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long taskId) {
        Long userId = Long.parseLong(userDetails.getUsername());
        taskService.deleteTask(userId, taskId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 获取任务详情
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> getTask(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long taskId) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(taskService.getTask(userId, taskId));
    }

    /**
     * 获取任务列表
     */
    @GetMapping
    public ResponseEntity<Page<Map<String, Object>>> getTasks(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) Boolean onlyActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = Long.parseLong(userDetails.getUsername());
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(taskService.getTasks(userId, onlyActive, pageable));
    }

    /**
     * 搜索任务
     */
    @GetMapping("/search")
    public ResponseEntity<Page<Map<String, Object>>> searchTasks(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = Long.parseLong(userDetails.getUsername());
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(taskService.searchTasks(userId, keyword, pageable));
    }

    /**
     * 启用任务
     */
    @PostMapping("/{taskId}/enable")
    public ResponseEntity<Map<String, Object>> enableTask(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long taskId) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(taskService.toggleTaskStatus(userId, taskId, true));
    }

    /**
     * 禁用任务
     */
    @PostMapping("/{taskId}/disable")
    public ResponseEntity<Map<String, Object>> disableTask(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long taskId) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(taskService.toggleTaskStatus(userId, taskId, false));
    }

    /**
     * 手动执行任务
     */
    @PostMapping("/{taskId}/execute")
    public ResponseEntity<Map<String, Object>> executeTask(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long taskId) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(taskService.executeTask(userId, taskId));
    }

    /**
     * 获取统计信息
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(taskService.getStatistics(userId));
    }
}
