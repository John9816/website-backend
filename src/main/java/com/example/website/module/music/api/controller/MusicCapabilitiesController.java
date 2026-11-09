package com.example.website.module.music.api.controller;
import com.example.website.common.ApiResponse;
import com.example.website.module.identity.application.UserScopeService;
import com.example.website.module.music.application.*;
import com.example.website.module.music.api.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.*;
import java.util.List;
@RestController
@RequestMapping("/api/v1/music")
@RequiredArgsConstructor
public class MusicCapabilitiesController {
    private final UserScopeService users;
    private final QishuiMusicClient qishui;
    private final TtmlLyricsClient ttml;
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping("/qishui/search/suggestions")
    public ApiResponse<List<String>> suggestions(HttpServletRequest request, @RequestParam String keyword) {
        users.requireAuthenticatedUserId(request); return ApiResponse.ok(qishui.searchSuggestions(keyword));
    }
    @GetMapping("/qishui/song")
    public ApiResponse<SongSearchItem> song(HttpServletRequest request, @RequestParam String id) {
        users.requireAuthenticatedUserId(request);
        if (id == null || !id.matches("[0-9]{1,24}")) throw new com.example.website.common.BusinessException(400, "Invalid Qishui song ID");
        return ApiResponse.ok(qishui.fetchSongInfo(id));
    }
    @GetMapping("/lyrics/ttml")
    public ApiResponse<TtmlLyricsView> ttml(HttpServletRequest request, @RequestParam String source, @RequestParam String id) {
        users.requireAuthenticatedUserId(request); return ApiResponse.ok(ttml.fetch(MusicSource.of(source), id));
    }
}
