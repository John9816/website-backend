package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.kb.KbPublicDocView;
import com.example.website.service.kb.KbDocShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/kb/share")
@RequiredArgsConstructor
public class PublicKnowledgeShareController {

    private final KbDocShareService shareService;

    @GetMapping("/{token}")
    public ApiResponse<KbPublicDocView> view(@PathVariable String token) {
        return ApiResponse.ok(shareService.view(token));
    }
}
