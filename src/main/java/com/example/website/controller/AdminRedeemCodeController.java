package com.example.website.controller;

import com.example.website.common.ApiResponse;
import com.example.website.dto.PageView;
import com.example.website.dto.membership.RedeemCodeAdminView;
import com.example.website.dto.membership.RedeemCodeBatchView;
import com.example.website.dto.membership.RedeemCodeGenerateRequest;
import com.example.website.entity.MembershipType;
import com.example.website.service.MembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/admin/redeem-codes")
@RequiredArgsConstructor
public class AdminRedeemCodeController {

    private final MembershipService membershipService;

    @PostMapping
    public ApiResponse<RedeemCodeBatchView> generate(HttpServletRequest request,
                                                     @Valid @RequestBody RedeemCodeGenerateRequest req) {
        Long adminUserId = (Long) request.getAttribute("userId");
        MembershipType type = MembershipType.parse(req.getType());
        return ApiResponse.ok(membershipService.generateCodes(adminUserId, type, req.getQuantity()));
    }

    @GetMapping
    public ApiResponse<PageView<RedeemCodeAdminView>> list(@RequestParam(required = false) String type,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(membershipService.listCodes(type, status, page, size));
    }

    @PostMapping("/{id}/revoke")
    public ApiResponse<RedeemCodeAdminView> revoke(@PathVariable Long id) {
        return ApiResponse.ok(membershipService.revokeCode(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        membershipService.deleteRevokedCode(id);
        return ApiResponse.ok();
    }
}
