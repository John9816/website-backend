package com.example.website.module.membership.api.controller;

import com.example.website.common.ApiResponse;
import com.example.website.module.membership.api.dto.MembershipView;
import com.example.website.module.membership.api.dto.RedeemCodeRequest;
import com.example.website.module.membership.api.dto.RedeemResultView;
import com.example.website.module.membership.application.MembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/user/membership")
@RequiredArgsConstructor
public class MembershipController {

    private final MembershipService membershipService;

    @GetMapping
    public ApiResponse<MembershipView> current(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return ApiResponse.ok(membershipService.getMembership(userId));
    }

    @PostMapping("/redeem")
    public ApiResponse<RedeemResultView> redeem(HttpServletRequest request,
                                                @Valid @RequestBody RedeemCodeRequest req) {
        Long userId = (Long) request.getAttribute("userId");
        return ApiResponse.ok(membershipService.redeem(userId, req.getCode()));
    }
}
