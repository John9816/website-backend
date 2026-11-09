package com.example.website.module.music.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.module.identity.application.UserScopeService;
import com.example.website.module.music.api.dto.QishuiDiscoveryView;
import com.example.website.module.music.api.dto.QishuiFeedModeView;
import com.example.website.module.music.api.dto.QishuiRoamingPageView;
import com.example.website.module.music.application.QishuiFeedClient;
import com.example.website.module.music.application.QishuiRoamingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;

@RestController
@RequestMapping("/api/v1/music/qishui")
@RequiredArgsConstructor
public class QishuiFeedController {
    private final QishuiFeedClient feed;
    private final QishuiRoamingService roaming;
    private final UserScopeService users;

    /** Also covers validation and service failures handled by the global exception advice. */
    @ModelAttribute
    public void preventCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping("/discovery")
    public ResponseEntity<ApiResponse<QishuiDiscoveryView>> discovery(HttpServletRequest request) {
        users.requireAuthenticatedUserId(request);
        return response(feed.discovery());
    }

    @GetMapping("/roaming/modes")
    public ResponseEntity<ApiResponse<List<QishuiFeedModeView>>> modes(HttpServletRequest request) {
        return response(roaming.modes(users.requireAuthenticatedUserId(request)));
    }

    @PostMapping("/roaming/sessions")
    public ResponseEntity<ApiResponse<QishuiRoamingPageView>> start(
            HttpServletRequest request, @RequestParam(required = false) String modeId) {
        return response(roaming.start(users.requireAuthenticatedUserId(request), modeId));
    }

    @PostMapping("/roaming/sessions/{sessionId}/next")
    public ResponseEntity<ApiResponse<QishuiRoamingPageView>> next(
            HttpServletRequest request, @PathVariable String sessionId,
            @RequestParam long sequence, @RequestParam(required = false) String modeId) {
        return response(roaming.next(users.requireAuthenticatedUserId(request), sessionId, sequence, modeId));
    }

    @DeleteMapping("/roaming/sessions/{sessionId}")
    public ResponseEntity<ApiResponse<Void>> close(HttpServletRequest request, @PathVariable String sessionId) {
        roaming.close(users.requireAuthenticatedUserId(request), sessionId);
        return response(null);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> invalidParameter() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(ApiResponse.error(400, "A valid numeric roaming sequence is required"));
    }

    private <T> ResponseEntity<ApiResponse<T>> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value));
    }
}
