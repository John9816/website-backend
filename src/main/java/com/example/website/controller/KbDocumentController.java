package com.example.website.controller;

import com.example.website.service.kb.KbDocumentService;
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
@RequestMapping("/api/kb/documents")
@RequiredArgsConstructor
public class KbDocumentController {

    private final KbDocumentService documentService;

    /**
     * 创建文档
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createDocument(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(documentService.createDocument(userId, request));
    }

    /**
     * 更新文档
     */
    @PutMapping("/{documentId}")
    public ResponseEntity<Map<String, Object>> updateDocument(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long documentId,
            @RequestBody Map<String, Object> request) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(documentService.updateDocument(userId, documentId, request));
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> deleteDocument(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long documentId) {
        Long userId = Long.parseLong(userDetails.getUsername());
        documentService.deleteDocument(userId, documentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 获取文档详情
     */
    @GetMapping("/{documentId}")
    public ResponseEntity<Map<String, Object>> getDocument(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long documentId) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(documentService.getDocument(userId, documentId));
    }

    /**
     * 获取空间的文档列表
     */
    @GetMapping
    public ResponseEntity<Page<Map<String, Object>>> getDocuments(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam Long spaceId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = Long.parseLong(userDetails.getUsername());
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(documentService.getDocuments(userId, spaceId, status, pageable));
    }

    /**
     * 获取子文档
     */
    @GetMapping("/children")
    public ResponseEntity<List<Map<String, Object>>> getChildDocuments(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam Long spaceId,
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false) String status) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(documentService.getChildDocuments(userId, spaceId, parentId, status));
    }

    /**
     * 搜索文档
     */
    @GetMapping("/search")
    public ResponseEntity<Page<Map<String, Object>>> searchDocuments(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam Long spaceId,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = Long.parseLong(userDetails.getUsername());
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(documentService.searchDocuments(userId, spaceId, keyword, pageable));
    }

    /**
     * 生成分享链接
     */
    @PostMapping("/{documentId}/share")
    public ResponseEntity<Map<String, Object>> generateShareLink(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long documentId) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(documentService.generateShareLink(userId, documentId));
    }

    /**
     * 复制文档
     */
    @PostMapping("/{documentId}/duplicate")
    public ResponseEntity<Map<String, Object>> duplicateDocument(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long documentId) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(documentService.duplicateDocument(userId, documentId));
    }

    /**
     * 获取热门文档
     */
    @GetMapping("/popular")
    public ResponseEntity<Page<Map<String, Object>>> getPopularDocuments(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long userId = Long.parseLong(userDetails.getUsername());
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(documentService.getPopularDocuments(userId, pageable));
    }

    /**
     * 获取统计信息
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam Long spaceId) {
        Long userId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(documentService.getStatistics(userId, spaceId));
    }
}

/**
 * 公开访问的文档分享Controller
 */
@RestController
@RequestMapping("/api/public/kb")
@RequiredArgsConstructor
class KbDocumentPublicController {

    private final KbDocumentService documentService;

    /**
     * 通过分享token访问文档
     */
    @GetMapping("/share/{shareToken}")
    public ResponseEntity<Map<String, Object>> getSharedDocument(@PathVariable String shareToken) {
        return ResponseEntity.ok(documentService.getDocumentByShareToken(shareToken));
    }
}
