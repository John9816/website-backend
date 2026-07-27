package com.example.website.dto;

import com.example.website.entity.User;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserDetailView {

    // Basic identity
    private Long id;
    private String username;
    private String email;
    private String role;
    private boolean enabled;
    private String avatarUrl;

    // Registration / source
    private String registerIp;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Activity
    private LocalDateTime lastLoginAt;
    private String lastLoginIp;
    private int loginCount;
    private long activeSessions;

    // Content assets
    private long imageCount;
    private long articleCount;
    private long kbDocCount;
    private long conversationCount;
    private long musicFavoriteCount;
    private long playlistCount;
    private long playHistoryCount;

    public static AdminUserDetailView from(User user) {
        AdminUserDetailView view = new AdminUserDetailView();
        view.id = user.getId();
        view.username = user.getUsername();
        view.email = user.getEmail();
        view.role = User.ROLE_ADMIN.equalsIgnoreCase(user.getRole()) ? User.ROLE_ADMIN : User.ROLE_USER;
        view.enabled = user.isEnabled();
        view.avatarUrl = user.getAvatarUrl();
        view.registerIp = user.getRegisterIp();
        view.createdAt = user.getCreatedAt();
        view.updatedAt = user.getUpdatedAt();
        view.lastLoginAt = user.getLastLoginAt();
        view.lastLoginIp = user.getLastLoginIp();
        view.loginCount = user.getLoginCount();
        return view;
    }
}
