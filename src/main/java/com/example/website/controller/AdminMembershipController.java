package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.membership.BatchMembershipExtendRequest;
import com.example.website.entity.MembershipType;
import com.example.website.service.MembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/admin/membership")
@RequiredArgsConstructor
public class AdminMembershipController {

    private final MembershipService membershipService;

    @PostMapping("/batch-extend")
    public ApiResponse<Void> batchExtend(HttpServletRequest request,
                                         @Valid @RequestBody BatchMembershipExtendRequest req) {
        Long adminUserId = (Long) request.getAttribute("userId");
        MembershipType type = MembershipType.parse(req.getType());
        membershipService.batchExtendMembership(adminUserId, req.getUserIds(), type);
        return ApiResponse.ok();
    }
}
