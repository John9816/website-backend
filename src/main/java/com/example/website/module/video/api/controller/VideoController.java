package com.example.website.module.video.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.module.video.api.dto.VideoCategoryView;
import com.example.website.module.video.api.dto.VideoDetailView;
import com.example.website.module.video.api.dto.VideoFilterView;
import com.example.website.module.video.api.dto.VideoPlayView;
import com.example.website.module.video.api.dto.VideoSearchPageView;
import com.example.website.module.video.api.dto.VideoSourceView;
import com.example.website.module.membership.application.MembershipService;
import com.example.website.module.video.application.NcatVideoService;
import com.example.website.module.video.application.VideoSourceGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/api/v1/videos")
public class VideoController {

    private final NcatVideoService videoService;
    private final MembershipService membershipService;
    private final VideoSourceGateway sourceGateway;

    /** Kept for existing unit tests and callers that only use the legacy source. */
    public VideoController(NcatVideoService videoService, MembershipService membershipService) {
        this(videoService, membershipService, null);
    }

    @Autowired
    public VideoController(NcatVideoService videoService,
                           MembershipService membershipService,
                           VideoSourceGateway sourceGateway) {
        this.videoService = videoService;
        this.membershipService = membershipService;
        this.sourceGateway = sourceGateway;
    }

    @GetMapping("/sources")
    public ApiResponse<List<VideoSourceView>> sources() {
        requireGateway();
        return ApiResponse.ok(sourceGateway.sources());
    }

    public ApiResponse<List<VideoCategoryView>> categories() {
        return categories(null);
    }

    @GetMapping("/categories")
    public ApiResponse<List<VideoCategoryView>> categories(
            @RequestParam(required = false) String source) {
        return ApiResponse.ok(hasSource(source) ? sourceGateway.categories(source) : videoService.categories());
    }

    public ApiResponse<VideoFilterView> filters() {
        return filters(null);
    }

    @GetMapping("/filters")
    public ApiResponse<VideoFilterView> filters(@RequestParam(required = false) String source) {
        return ApiResponse.ok(hasSource(source) ? sourceGateway.filters(source) : videoService.filters());
    }

    @GetMapping("/discover")
    public ApiResponse<VideoSearchPageView> discover(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String year,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String order,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(hasSource(source)
                ? sourceGateway.discover(source, category, year, region, genre, order, page, pageSize)
                : videoService.discover(category, year, region, genre, order, page, pageSize));
    }

    public ApiResponse<VideoSearchPageView> search(String keyword, String category, int page, int pageSize) {
        return search(keyword, category, null, page, pageSize);
    }

    @GetMapping("/search")
    public ApiResponse<VideoSearchPageView> search(
            @RequestParam String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(hasSource(source)
                ? sourceGateway.search(source, keyword, category, page, pageSize)
                : videoService.search(keyword, category, page, pageSize));
    }

    public ApiResponse<VideoDetailView> detail(String id) {
        return detail(id, null);
    }

    @GetMapping("/detail")
    public ApiResponse<VideoDetailView> detail(@RequestParam String id,
                                               @RequestParam(required = false) String source) {
        return ApiResponse.ok(hasSource(source) ? sourceGateway.detail(source, id) : videoService.detail(id));
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<VideoDetailView> detailById(@PathVariable String id,
                                                   @RequestParam(required = false) String source) {
        return detail(id, source);
    }

    public ApiResponse<VideoPlayView> play(HttpServletRequest request, String id,
                                           String episode, String line) {
        return play(request, id, episode, line, null);
    }

    @GetMapping("/play")
    public ApiResponse<VideoPlayView> play(HttpServletRequest request,
                                           @RequestParam String id,
                                           @RequestParam(required = false) String episode,
                                           @RequestParam(required = false) String line,
                                           @RequestParam(required = false) String source) {
        Long userId = request == null ? null : (Long) request.getAttribute("userId");
        membershipService.requireActiveMembership(userId);
        return ApiResponse.ok(hasSource(source)
                ? sourceGateway.play(source, id, episode, line)
                : videoService.play(id, episode, line));
    }

    @GetMapping("/{id}/play")
    public ApiResponse<VideoPlayView> playById(HttpServletRequest request,
                                               @PathVariable String id,
                                               @RequestParam(required = false) String episode,
                                               @RequestParam(required = false) String line,
                                               @RequestParam(required = false) String source) {
        return play(request, id, episode, line, source);
    }

    private boolean hasSource(String source) {
        return source != null && !source.trim().isEmpty();
    }

    private void requireGateway() {
        if (sourceGateway == null) throw new IllegalStateException("影视源网关未配置");
    }
}
