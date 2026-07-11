package com.example.website.dto;

import com.example.website.entity.User;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserView {

    private Long id;
    private String username;
    private String email;
    private String role;
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AdminUserView from(User user) {
        AdminUserView view = new AdminUserView();
        view.id = user.getId();
        view.username = user.getUsername();
        view.email = user.getEmail();
        view.role = User.ROLE_ADMIN.equalsIgnoreCase(user.getRole()) ? User.ROLE_ADMIN : User.ROLE_USER;
        view.enabled = user.isEnabled();
        view.createdAt = user.getCreatedAt();
        view.updatedAt = user.getUpdatedAt();
        return view;
    }
}
