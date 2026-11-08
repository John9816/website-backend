package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.music.QishuiAccountView;
import com.example.website.service.UserScopeService;
import com.example.website.service.music.QishuiAccountService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/music/qishui")
@RequiredArgsConstructor
public class QishuiAccountController {
    private final QishuiAccountService service;
    private final UserScopeService users;

    @PostMapping("/auth/qr")
    public ResponseEntity<ApiResponse<QishuiAccountView>> start(HttpServletRequest request) {
        return response(service.start(users.requireAuthenticatedUserId(request)));
    }

    @GetMapping("/auth/qr/{sessionId}")
    public ResponseEntity<ApiResponse<QishuiAccountView>> poll(
            HttpServletRequest request, @PathVariable String sessionId) {
        return response(service.poll(users.requireAuthenticatedUserId(request), sessionId));
    }

    @GetMapping("/account")
    public ResponseEntity<ApiResponse<QishuiAccountView>> account(HttpServletRequest request) {
        return response(service.account(users.requireAuthenticatedUserId(request)));
    }

    @PostMapping("/account/refresh")
    public ResponseEntity<ApiResponse<QishuiAccountView>> refresh(HttpServletRequest request) {
        return response(service.refresh(users.requireAuthenticatedUserId(request)));
    }

    @DeleteMapping("/account")
    public ResponseEntity<ApiResponse<Void>> delete(HttpServletRequest request) {
        service.delete(users.requireAuthenticatedUserId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(null));
    }

    private ResponseEntity<ApiResponse<QishuiAccountView>> response(QishuiAccountView view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(view));
    }
}
