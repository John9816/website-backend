package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.music.LyricView;
import com.example.website.dto.music.PlaylistDetailView;
import com.example.website.dto.music.PlaylistListView;
import com.example.website.dto.music.PlayInfo;
import com.example.website.dto.music.SearchResultView;
import com.example.website.dto.music.ToplistDetailView;
import com.example.website.dto.music.ToplistListView;
import com.example.website.service.music.MusicLibraryService;
import com.example.website.service.music.MusicService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@RestController
@RequestMapping("/api/v1/music")
@RequiredArgsConstructor
public class MusicController {

    private final MusicService musicService;
    private final MusicLibraryService musicLibraryService;

    @GetMapping("/search")
    public ApiResponse<SearchResultView> search(@RequestParam String source,
                                                @RequestParam String keyword,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(musicService.search(source, keyword, type, page, pageSize));
    }

    @GetMapping("/play")
    public ApiResponse<PlayInfo> play(HttpServletRequest request,
                                      @RequestParam String source,
                                      @RequestParam String id,
                                      @RequestParam(required = false) String quality) {
        PlayInfo info = musicService.play(source, id, quality);
        Long userId = request == null ? null : (Long) request.getAttribute("userId");
        if (userId != null) {
            try {
                musicLibraryService.recordPlay(userId, info);
            } catch (Exception e) {
                log.warn("Music played but failed to persist history: {}", e.getMessage());
            }
        }
        return ApiResponse.ok(info);
    }

    @GetMapping("/lyric")
    public ApiResponse<LyricView> lyric(@RequestParam String source,
                                        @RequestParam String id) {
        return ApiResponse.ok(musicService.lyric(source, id));
    }

    @GetMapping("/toplist")
    public ApiResponse<ToplistListView> toplist(@RequestParam String source) {
        return ApiResponse.ok(musicService.toplists(source));
    }

    @GetMapping("/toplist/detail")
    public ApiResponse<ToplistDetailView> toplistDetail(@RequestParam String source,
                                                        @RequestParam String id,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "30") int pageSize) {
        return ApiResponse.ok(musicService.toplistDetail(source, id, page, pageSize));
    }

    @GetMapping("/playlist")
    public ApiResponse<PlaylistListView> playlists(@RequestParam String source,
                                                   @RequestParam(required = false) String category,
                                                   @RequestParam(required = false) String order,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(musicService.playlists(source, category, order, page, pageSize));
    }

    @GetMapping("/playlist/detail")
    public ApiResponse<PlaylistDetailView> playlistDetail(@RequestParam String source,
                                                          @RequestParam String id,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "30") int pageSize) {
        return ApiResponse.ok(musicService.playlistDetail(source, id, page, pageSize));
    }

    @GetMapping("/new")
    public ApiResponse<ToplistDetailView> newSongs(@RequestParam String source,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "30") int pageSize) {
        return ApiResponse.ok(musicService.newSongs(source, page, pageSize));
    }
}
