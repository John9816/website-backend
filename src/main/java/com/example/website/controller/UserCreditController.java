package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.UserCreditView;
import com.example.website.service.UserCreditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequiredArgsConstructor
public class UserCreditController {
    private final UserCreditService userCreditService;

    @GetMapping("/api/user/credits")
    public ApiResponse<UserCreditView> credits(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) return ApiResponse.error(401, "Unauthorized");
        return ApiResponse.ok(userCreditService.get(userId));
    }

    @PostMapping("/api/user/check-in")
    public ApiResponse<UserCreditView> checkIn(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) return ApiResponse.error(401, "Unauthorized");
        return ApiResponse.ok(userCreditService.checkIn(userId));
    }
}
