package com.example.website.module.editor.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.module.editor.application.EditorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 学城文档公开接口：只读分享、文件访问。由 SecurityConfig 的 /api/public/** GET permitAll 保护。 */
@RestController
@RequestMapping("/api/public/editor")
@RequiredArgsConstructor
public class EditorPublicController {

    private final EditorService service;

    @GetMapping("/share/{token}")
    public ResponseEntity<?> readShare(@PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.ok(service.readShared(token)));
    }

    @GetMapping("/files/{name:.+}")
    public ResponseEntity<byte[]> file(@PathVariable String name) {
        byte[] data = service.readFile(name);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .contentType(MediaType.parseMediaType(service.fileContentType(name)))
                .body(data);
    }
}
