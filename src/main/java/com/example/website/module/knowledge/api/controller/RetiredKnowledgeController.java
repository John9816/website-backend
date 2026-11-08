package com.example.website.module.knowledge.api.controller;

import com.example.website.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Retire all legacy entry points without deleting archived document data. */
@RestController
public class RetiredKnowledgeController {
    @RequestMapping({"/api/user/kb/**", "/api/admin/kb/**", "/api/public/kb/**",
            "/api/v1/kb/**", "/api/kb/**", "/api/share/doc/**"})
    public ResponseEntity<ApiResponse<Void>> retired() {
        return ResponseEntity.status(410).header("Cache-Control", "no-store")
                .body(ApiResponse.error(410, "旧知识库已下线，请使用学城文档"));
    }
}
