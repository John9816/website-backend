package com.example.website.dto;

import com.example.website.entity.User;
import lombok.Data;

@Data
public class CurrentUserView {

    private Long id;
    private String username;
    private String role;
    private boolean canManageSystemConfig;

    public static CurrentUserView from(User user) {
        CurrentUserView view = new CurrentUserView();
        view.id = user.getId();
        view.username = user.getUsername();
        view.role = normalizeRole(user.getRole());
        view.canManageSystemConfig = User.ROLE_ADMIN.equals(view.role);
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
