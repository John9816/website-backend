package com.example.website.controller;

import com.example.website.service.kb.KbDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/share/doc")
@RequiredArgsConstructor
public class KbDocumentShareController {

    private final KbDocumentService documentService;

    /**
     * 通过分享令牌获取文档（无需登录）
     */
    @GetMapping("/{shareToken}")
    public ResponseEntity<Map<String, Object>> getSharedDocument(@PathVariable String shareToken) {
        return ResponseEntity.ok(documentService.getDocumentByShareToken(shareToken));
    }
}
