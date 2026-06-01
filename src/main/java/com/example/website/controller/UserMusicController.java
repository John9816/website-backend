package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.PageView;
import com.example.website.dto.music.BatchFavoriteStatusRequest;
import com.example.website.dto.music.MusicFavoriteRequest;
import com.example.website.dto.music.MusicFavoriteStatusView;
import com.example.website.dto.music.MusicFavoriteView;
import com.example.website.dto.music.MusicHistoryView;
import com.example.website.dto.music.MusicShareRequest;
import com.example.website.dto.music.MusicShareView;
import com.example.website.dto.music.PlaylistImportRequest;
import com.example.website.dto.music.PlaylistRenameRequest;
import com.example.website.dto.music.UserPlaylistDetailView;
import com.example.website.dto.music.UserPlaylistView;
import com.example.website.service.UserScopeService;
import com.example.website.service.music.MusicLibraryService;
import com.example.website.service.music.MusicShareService;
import com.example.website.service.music.UserPlaylistService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/user/music")
@RequiredArgsConstructor
public class UserMusicController {

    private final MusicLibraryService musicLibraryService;
    private final MusicShareService musicShareService;
    private final UserPlaylistService userPlaylistService;
    private final UserScopeService userScopeService;

    @GetMapping("/history")
    public ApiResponse<PageView<MusicHistoryView>> history(HttpServletRequest request,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(musicLibraryService.listHistory(userId, page, size));
    }

    @DeleteMapping("/history/{id}")
    public ApiResponse<Void> deleteHistory(HttpServletRequest request, @PathVariable Long id) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        musicLibraryService.deleteHistory(userId, id);
        return ApiResponse.ok();
    }

    @GetMapping("/favorites")
    public ApiResponse<PageView<MusicFavoriteView>> favorites(HttpServletRequest request,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(musicLibraryService.listFavorites(userId, page, size));
    }

    @PostMapping("/favorites")
    public ApiResponse<MusicFavoriteView> saveFavorite(HttpServletRequest request,
                                                       @Valid @RequestBody MusicFavoriteRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(musicLibraryService.saveFavorite(userId, req));
    }

    @DeleteMapping("/favorites")
    public ApiResponse<Void> deleteFavorite(HttpServletRequest request,
                                            @RequestParam String source,
                                            @RequestParam String songId) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        musicLibraryService.deleteFavorite(userId, source, songId);
        return ApiResponse.ok();
    }

    @GetMapping("/favorites/status")
    public ApiResponse<MusicFavoriteStatusView> favoriteStatus(HttpServletRequest request,
                                                               @RequestParam String source,
                                                               @RequestParam String songId) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(musicLibraryService.favoriteStatus(userId, source, songId));
    }

    @PostMapping("/favorites/status")
    public ApiResponse<List<MusicFavoriteStatusView>> batchFavoriteStatus(HttpServletRequest request,
                                                                          @Valid @RequestBody BatchFavoriteStatusRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(musicLibraryService.batchFavoriteStatus(userId, req.getSource(), req.getSongIds()));
    }

    @GetMapping("/shares/status")
    public ApiResponse<MusicShareView> shareStatus(HttpServletRequest request,
                                                   @RequestParam String source,
                                                   @RequestParam String songId) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(musicShareService.get(userId, source, songId));
    }

    @PostMapping("/shares")
    public ApiResponse<MusicShareView> saveShare(HttpServletRequest request,
                                                 @Valid @RequestBody MusicShareRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(musicShareService.save(userId, req));
    }

    @DeleteMapping("/shares")
    public ApiResponse<Void> deleteShare(HttpServletRequest request,
                                         @RequestParam String source,
                                         @RequestParam String songId) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        musicShareService.delete(userId, source, songId);
        return ApiResponse.ok();
    }

    @PostMapping("/playlists/import")
    public ApiResponse<UserPlaylistView> importPlaylist(HttpServletRequest request,
                                                        @Valid @RequestBody PlaylistImportRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(userPlaylistService.importFromUrl(userId, req.getUrl()));
    }

    @GetMapping("/playlists")
    public ApiResponse<PageView<UserPlaylistView>> listPlaylists(HttpServletRequest request,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(userPlaylistService.list(userId, page, size));
    }

    @GetMapping("/playlists/{id}")
    public ApiResponse<UserPlaylistDetailView> playlistDetail(HttpServletRequest request,
                                                              @PathVariable Long id,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "30") int size) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(userPlaylistService.detail(userId, id, page, size));
    }

    @DeleteMapping("/playlists/{id}")
    public ApiResponse<Void> deletePlaylist(HttpServletRequest request, @PathVariable Long id) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        userPlaylistService.delete(userId, id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/playlists/{id}/items/{itemId}")
    public ApiResponse<Void> removePlaylistItem(HttpServletRequest request,
                                                @PathVariable Long id,
                                                @PathVariable Long itemId) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        userPlaylistService.removeItem(userId, id, itemId);
        return ApiResponse.ok();
    }

    @PatchMapping("/playlists/{id}")
    public ApiResponse<UserPlaylistView> renamePlaylist(HttpServletRequest request,
                                                        @PathVariable Long id,
                                                        @Valid @RequestBody PlaylistRenameRequest req) {
        Long userId = userScopeService.requireAuthenticatedUserId(request);
        return ApiResponse.ok(userPlaylistService.rename(userId, id, req.getName()));
    }
}
