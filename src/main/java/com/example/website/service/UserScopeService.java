package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.entity.User;
import com.example.website.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;

@Service
@RequiredArgsConstructor
public class UserScopeService {

    private final UserRepository userRepository;

    public Long requireAuthenticatedUserId(HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        return userId;
    }

    public Long currentUserOrAdmin(HttpServletRequest request) {
        return adminUserId();
    }

    public Long adminUserId() {
        return userRepository.findFirstByRoleOrderByIdAsc(User.ROLE_ADMIN)
                .map(User::getId)
                .orElseThrow(() -> new BusinessException(500, "Admin user not found"));
    }

    private Long currentUserId(HttpServletRequest request) {
        return request == null ? null : (Long) request.getAttribute("userId");
    }
}
