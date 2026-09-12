package com.example.website.dto.membership;

import lombok.Data;

@Data
public class RedeemResultView {

    private String message;
    private MembershipView membership;

    public RedeemResultView(String message, MembershipView membership) {
        this.message = message;
        this.membership = membership;
    }
}
