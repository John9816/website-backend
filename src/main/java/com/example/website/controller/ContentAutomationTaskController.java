package com.example.website.controller;

import com.example.website.service.UserScopeService;
import com.example.website.service.content.ContentAutomationTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/content/automation")
@RequiredArgsConstructor
public class ContentAutomationTaskController {

    private final ContentAutomationTaskService taskService;
    private final UserScopeService userScopeService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> createTask(HttpServletRequest request,
                                                           @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(taskService.createTask(currentUserId(request), body));
    }

    @PutMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> updateTask(HttpServletRequest request,
                                                           @PathVariable Long taskId,
                                                           @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(taskService.updateTask(currentUserId(request), taskId, body));
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> deleteTask(HttpServletRequest request, @PathVariable Long taskId) {
        taskService.deleteTask(currentUserId(request), taskId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> getTask(HttpServletRequest request, @PathVariable Long taskId) {
        return ResponseEntity.ok(taskService.getTask(currentUserId(request), taskId));
    }

    @GetMapping
    public ResponseEntity<Page<Map<String, Object>>> getTasks(HttpServletRequest request,
                                                               @RequestParam(required = false) Boolean onlyActive,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), boundedSize(size));
        return ResponseEntity.ok(taskService.getTasks(currentUserId(request), onlyActive, pageable));
    }

    @GetMapping("/search")
    public ResponseEntity<Page<Map<String, Object>>> searchTasks(HttpServletRequest request,
                                                                  @RequestParam String keyword,
                                                                  @RequestParam(defaultValue = "0") int page,
                                                                  @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), boundedSize(size));
        return ResponseEntity.ok(taskService.searchTasks(currentUserId(request), keyword, pageable));
    }

    @PostMapping("/{taskId}/enable")
    public ResponseEntity<Map<String, Object>> enableTask(HttpServletRequest request, @PathVariable Long taskId) {
        return ResponseEntity.ok(taskService.toggleTaskStatus(currentUserId(request), taskId, true));
    }

    @PostMapping("/{taskId}/disable")
    public ResponseEntity<Map<String, Object>> disableTask(HttpServletRequest request, @PathVariable Long taskId) {
        return ResponseEntity.ok(taskService.toggleTaskStatus(currentUserId(request), taskId, false));
    }

    @PostMapping("/{taskId}/execute")
    public ResponseEntity<Map<String, Object>> executeTask(HttpServletRequest request, @PathVariable Long taskId) {
        return ResponseEntity.ok(taskService.executeTask(currentUserId(request), taskId));
    }

    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics(HttpServletRequest request) {
        return ResponseEntity.ok(taskService.getStatistics(currentUserId(request)));
    }

    private Long currentUserId(HttpServletRequest request) {
        return userScopeService.requireAuthenticatedUserId(request);
    }

    private int boundedSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
