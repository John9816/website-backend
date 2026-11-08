package com.example.website.module.music.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.module.music.api.dto.MusicPublicShareView;
import com.example.website.module.music.application.MusicShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/music/share")
@RequiredArgsConstructor
public class PublicMusicShareController {

    private final MusicShareService shareService;

    @GetMapping("/{token}")
    public ApiResponse<MusicPublicShareView> view(@PathVariable String token) {
        return ApiResponse.ok(shareService.view(token));
    }
}
