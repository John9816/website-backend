package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.CategoryView;
import com.example.website.dto.music.*;
import com.example.website.service.CategoryService;
import com.example.website.service.UserScopeService;
import com.example.website.service.music.MusicLibraryService;
import com.example.website.service.music.MusicService;
import com.example.website.service.MembershipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Legacy API路径兼容Controller
 * 为了兼容前端的旧API路径（/nav, /music等），提供重定向支持
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LegacyApiController {

    private final CategoryService categoryService;
    private final UserScopeService userScopeService;
    private final MusicService musicService;
    private final MusicLibraryService musicLibraryService;
    private final MembershipService membershipService;

    // ==================== 导航API兼容 ====================

    /**
     * 兼容旧的导航API路径
     * 前端调用: /nav 或 /nav/categories
     * 实际处理: /api/public/nav
     */
    @GetMapping("/nav")
    public ApiResponse<List<CategoryView>> getNav(HttpServletRequest request) {
        return ApiResponse.ok(categoryService.listAll(userScopeService.currentUserOrAdmin(request)));
    }

    @GetMapping("/nav/categories")
    public ApiResponse<List<CategoryView>> getNavCategories(HttpServletRequest request) {
        return ApiResponse.ok(categoryService.listAll(userScopeService.currentUserOrAdmin(request)));
    }

    // ==================== 音乐API兼容 ====================

    /**
     * 兼容旧的音乐搜索路径
     * 前端调用: /music/search
     * 实际处理: /api/v1/music/search
     */
    @GetMapping("/music/search")
    public ApiResponse<SearchResultView> musicSearch(@RequestParam String source,
                                                     @RequestParam String keyword,
                                                     @RequestParam(required = false) String type,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(musicService.search(source, keyword, type, page, pageSize));
    }

    /**
     * 兼容旧的音乐播放路径
     * 前端调用: /music/play
     * 实际处理: /api/v1/music/play
     */
    @GetMapping("/music/play")
    public ApiResponse<PlayInfo> musicPlay(HttpServletRequest request,
                                          @RequestParam String source,
                                          @RequestParam String id,
                                          @RequestParam(required = false) String quality,
                                          @RequestParam(defaultValue = "false") boolean prefetch) {
        Long userId = request == null ? null : (Long) request.getAttribute("userId");
        membershipService.requireActiveMembership(userId);
        PlayInfo info = musicService.play(source, id, quality);
        if (userId != null && !prefetch) {
            try {
                musicLibraryService.recordPlay(userId, info);
            } catch (Exception e) {
                log.warn("Music played but failed to persist history: {}", e.getMessage());
            }
        }
        return ApiResponse.ok(info);
    }

    /**
     * 兼容旧的歌词路径
     * 前端调用: /music/lyric
     * 实际处理: /api/v1/music/lyric
     */
    @GetMapping("/music/lyric")
    public ApiResponse<LyricView> musicLyric(@RequestParam String source,
                                            @RequestParam String id) {
        return ApiResponse.ok(musicService.lyric(source, id));
    }

    /**
     * 兼容旧的榜单列表路径
     */
    @GetMapping("/music/toplist")
    public ApiResponse<ToplistListView> musicToplist(@RequestParam String source) {
        return ApiResponse.ok(musicService.toplists(source));
    }

    /**
     * 兼容旧的榜单详情路径
     */
    @GetMapping("/music/toplist/detail")
    public ApiResponse<ToplistDetailView> musicToplistDetail(@RequestParam String source,
                                                             @RequestParam String id,
                                                             @RequestParam(defaultValue = "1") int page,
                                                             @RequestParam(defaultValue = "30") int pageSize) {
        return ApiResponse.ok(musicService.toplistDetail(source, id, page, pageSize));
    }

    /**
     * 兼容旧的歌单列表路径
     */
    @GetMapping("/music/playlist")
    public ApiResponse<PlaylistListView> musicPlaylists(@RequestParam String source,
                                                        @RequestParam(required = false) String category,
                                                        @RequestParam(required = false) String order,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(musicService.playlists(source, category, order, page, pageSize));
    }

    /**
     * 兼容旧的推荐歌单路径
     */
    @GetMapping("/music/playlist/recommend")
    public ApiResponse<PlaylistListView> musicRecommendedPlaylists(@RequestParam String source,
                                                                   @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(musicService.recommendedPlaylists(source, limit));
    }

    /**
     * 兼容旧的歌单详情路径
     */
    @GetMapping("/music/playlist/detail")
    public ApiResponse<PlaylistDetailView> musicPlaylistDetail(@RequestParam String source,
                                                               @RequestParam String id,
                                                               @RequestParam(defaultValue = "1") int page,
                                                               @RequestParam(defaultValue = "30") int pageSize) {
        return ApiResponse.ok(musicService.playlistDetail(source, id, page, pageSize));
    }

    /**
     * 兼容旧的新歌路径
     */
    @GetMapping("/music/new")
    public ApiResponse<ToplistDetailView> musicNewSongs(@RequestParam String source,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "30") int pageSize) {
        return ApiResponse.ok(musicService.newSongs(source, page, pageSize));
    }

    /**
     * 兼容旧的歌手信息路径
     */
    @GetMapping("/music/artist")
    public ApiResponse<List<ArtistDetailView>> musicArtist(@RequestParam String source,
                                                           @RequestParam List<String> ids) {
        return ApiResponse.ok(musicService.getArtists(source, ids));
    }

    /**
     * 兼容旧的专辑详情路径
     */
    @GetMapping("/music/album/detail")
    public ApiResponse<AlbumDetailView> musicAlbumDetail(@RequestParam String source,
                                                         @RequestParam String id,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "30") int pageSize) {
        return ApiResponse.ok(musicService.albumDetail(source, id, page, pageSize));
    }

    /**
     * 兼容旧的歌手作品路径
     */
    @GetMapping("/music/artist/works")
    public ApiResponse<ArtistWorksView> musicArtistWorks(@RequestParam String source,
                                                         @RequestParam String id,
                                                         @RequestParam(defaultValue = "song") String type,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "30") int pageSize) {
        return ApiResponse.ok(musicService.artistWorks(source, id, type, page, pageSize));
    }
}
