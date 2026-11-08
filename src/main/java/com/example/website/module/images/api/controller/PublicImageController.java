package com.example.website.module.images.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.module.images.api.dto.GeneratedImageView;
import com.example.website.shared.dto.PageView;
import com.example.website.module.images.application.GeneratedImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/image")
@RequiredArgsConstructor
public class PublicImageController {

    private final GeneratedImageService historyService;

    @GetMapping("/shared")
    public ApiResponse<PageView<GeneratedImageView>> shared(@RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(historyService.listShared(page, size));
    }
}
