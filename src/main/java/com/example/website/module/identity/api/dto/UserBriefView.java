package com.example.website.module.identity.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Minimal public identity for display in other modules (leaderboards, shares). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserBriefView {

    private String username;
    private String avatarUrl;
}
