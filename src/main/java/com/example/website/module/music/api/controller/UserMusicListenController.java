package com.example.website.module.music.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.module.identity.application.UserScopeService;
import com.example.website.module.music.api.dto.ListenTimeReportRequest;
import com.example.website.module.music.api.dto.MyListenTimeView;
import com.example.website.module.music.application.MusicListenTimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/** Authenticated listening-time endpoints (heartbeat report + my stats/ranks). */
@RestController
@RequestMapping("/api/user/music/listening")
@RequiredArgsConstructor
public class UserMusicListenController {

    private final MusicListenTimeService listenTimeService;
    private final UserScopeService userScopeService;

    /** Report real played seconds since the previous heartbeat (every ~30s). */
    @PostMapping("/heartbeat")
    public ApiResponse<Void> heartbeat(HttpServletRequest request,
                                        @RequestBody(required = false) ListenTimeReportRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        listenTimeService.report(userId, req == null ? null : req.getSeconds());
        return ApiResponse.ok();
    }

    /** My total/week/month listening time plus my rank on each leaderboard. */
    @GetMapping("/me")
    public ApiResponse<MyListenTimeView> me(HttpServletRequest request) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(listenTimeService.mine(userId));
    }
}
