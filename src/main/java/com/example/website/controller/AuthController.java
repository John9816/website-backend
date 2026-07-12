package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.ChangePasswordRequest;
import com.example.website.dto.CurrentUserView;
import com.example.website.dto.LoginRequest;
import com.example.website.dto.LoginResponse;
import com.example.website.dto.RegisterRequest;
import com.example.website.dto.UserProfileUpdateRequest;
import com.example.website.service.AuthService;
import com.example.website.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.concurrent.TimeUnit;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserProfileService userProfileService;

    @PostMapping("/api/auth/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.ok(authService.register(req));
    }

    @PostMapping("/api/auth/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req));
    }

    @GetMapping("/api/user/me")
    public ApiResponse<CurrentUserView> currentUser(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        return ApiResponse.ok(userProfileService.getCurrentUser(userId));
    }

    @PutMapping("/api/user/profile")
    public ApiResponse<CurrentUserView> updateProfile(HttpServletRequest request,
                                                      @Valid @RequestBody UserProfileUpdateRequest req) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        return ApiResponse.ok(userProfileService.updateProfile(userId, req));
    }

    @PostMapping("/api/user/avatar")
    public ApiResponse<CurrentUserView> updateAvatar(HttpServletRequest request,
                                                     @RequestParam("file") MultipartFile file) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        return ApiResponse.ok(userProfileService.updateAvatar(userId, file));
    }

    @GetMapping("/api/v1/user/avatar/{filename}")
    public ResponseEntity<?> avatar(@PathVariable String filename) {
        UserProfileService.AvatarFile avatar = userProfileService.readAvatar(filename);
        return ResponseEntity.ok()
                .contentType(avatar.mediaType())
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                .body(avatar.resource());
    }

    @PostMapping({"/api/admin/change-password", "/api/user/change-password"})
    public ApiResponse<Void> changePassword(HttpServletRequest request,
                                            @Valid @RequestBody ChangePasswordRequest req) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        authService.changePassword(userId, req.getOldPassword(), req.getNewPassword());
        return ApiResponse.ok();
    }
}
