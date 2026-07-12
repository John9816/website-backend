package com.example.website.dto;

import com.example.website.entity.User;
import lombok.Data;

@Data
public class CurrentUserView {

    private Long id;
    private String username;
    private String email;
    private String role;
    private boolean enabled;
    private boolean canManageSystemConfig;
    private String avatarUrl;

    public static CurrentUserView from(User user) {
        CurrentUserView view = new CurrentUserView();
        view.id = user.getId();
        view.username = user.getUsername();
        view.email = user.getEmail();
        view.role = normalizeRole(user.getRole());
        view.enabled = user.isEnabled();
        view.canManageSystemConfig = User.ROLE_ADMIN.equals(view.role);
        view.avatarUrl = user.getAvatarUrl();
        return view;
    }

    private static String normalizeRole(String role) {
        if (role == null) {
            return User.ROLE_USER;
        }
        String normalized = role.trim().toUpperCase(java.util.Locale.ROOT);
        return User.ROLE_ADMIN.equals(normalized) ? User.ROLE_ADMIN : User.ROLE_USER;
    }
}
