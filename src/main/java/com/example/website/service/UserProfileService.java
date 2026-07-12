package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.CurrentUserView;
import com.example.website.dto.UserProfileUpdateRequest;
import com.example.website.entity.User;
import com.example.website.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final AuthUserCacheService authUserCacheService;

    @Transactional(readOnly = true)
    public CurrentUserView getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));
        return CurrentUserView.from(user);
    }

    @Transactional
    public CurrentUserView updateProfile(Long userId, UserProfileUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));
        String username = normalizeUsername(request.getUsername());
        String email = normalizeEmail(request.getEmail());

        if (userRepository.existsByUsernameAndIdNot(username, userId)) {
            throw new BusinessException(409, "Username already exists");
        }
        if (userRepository.existsByEmailAndIdNot(email, userId)) {
            throw new BusinessException(409, "QQ email already exists");
        }

        user.setUsername(username);
        user.setEmail(email);
        User saved = userRepository.save(user);
        authUserCacheService.evict(saved.getId());
        return CurrentUserView.from(saved);
    }

    private String normalizeUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new BusinessException(400, "username is required");
        }
        return username.trim();
    }

    private String normalizeEmail(String email) {
        if (!StringUtils.hasText(email)) {
            throw new BusinessException(400, "QQ email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
