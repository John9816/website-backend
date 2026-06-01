package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.GeneratedImageView;
import com.example.website.dto.PageView;
import com.example.website.service.GeneratedImageService;
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
