package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.music.MusicPublicShareView;
import com.example.website.service.music.MusicShareService;
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
