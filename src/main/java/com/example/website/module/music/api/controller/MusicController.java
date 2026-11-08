package com.example.website.module.music.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.module.music.api.dto.ArtistDetailView;
import com.example.website.module.music.api.dto.ArtistWorksView;
import com.example.website.module.music.api.dto.AlbumDetailView;
import com.example.website.module.music.api.dto.LyricView;
import com.example.website.module.music.api.dto.PlaylistDetailView;
import com.example.website.module.music.api.dto.PlaylistListView;
import com.example.website.module.music.api.dto.PlayInfo;
import com.example.website.module.music.api.dto.SearchResultView;
import com.example.website.module.music.api.dto.ToplistDetailView;
import com.example.website.module.music.api.dto.ToplistListView;
import com.example.website.module.music.application.MusicHistoryRecorder;
import com.example.website.module.music.application.MusicService;
import com.example.website.module.membership.application.MembershipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/music")
@RequiredArgsConstructor
public class MusicController {

    private final MusicService musicService;
    private final MusicHistoryRecorder historyRecorder;
    private final MembershipService membershipService;

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
                                      @RequestParam(required = false) String quality,
                                      @RequestParam(defaultValue = "false") boolean prefetch) {
        Long userId = request == null ? null : (Long) request.getAttribute("userId");
        membershipService.requireActiveMembership(userId);
        PlayInfo info = musicService.play(source, id, quality);
        if (userId != null && !prefetch) {
            try {
                historyRecorder.submit(userId, info);
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

    @GetMapping("/playlist/recommend")
    public ApiResponse<PlaylistListView> recommendedPlaylists(@RequestParam String source,
                                                              @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(musicService.recommendedPlaylists(source, limit));
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

    @GetMapping("/artist")
    public ApiResponse<List<ArtistDetailView>> artist(@RequestParam String source,
                                                      @RequestParam List<String> ids) {
        return ApiResponse.ok(musicService.getArtists(source, ids));
    }

    @GetMapping("/album/detail")
    public ApiResponse<AlbumDetailView> albumDetail(@RequestParam String source,
                                                    @RequestParam String id,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "30") int pageSize) {
        return ApiResponse.ok(musicService.albumDetail(source, id, page, pageSize));
    }

    @GetMapping("/artist/works")
    public ApiResponse<ArtistWorksView> artistWorks(@RequestParam String source,
                                                    @RequestParam String id,
                                                    @RequestParam(defaultValue = "song") String type,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "30") int pageSize) {
        return ApiResponse.ok(musicService.artistWorks(source, id, type, page, pageSize));
    }
}
