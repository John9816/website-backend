package com.example.website.module.music.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.module.music.api.dto.ListenRankingView;
import com.example.website.module.music.application.MusicListenTimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public (GET /api/public/** is permitAll) week/month listening-time leaderboard. */
@RestController
@RequestMapping("/api/public/music/listening")
@RequiredArgsConstructor
public class PublicMusicListenRankingController {

    private final MusicListenTimeService listenTimeService;

    @GetMapping("/ranking")
    public ApiResponse<ListenRankingView> ranking(
            @RequestParam(defaultValue = "week") String period,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(listenTimeService.ranking(period, limit));
    }
}
