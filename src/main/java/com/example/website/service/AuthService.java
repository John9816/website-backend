package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.config.AppProperties;
import com.example.website.dto.LoginRequest;
import com.example.website.dto.LoginResponse;
import com.example.website.dto.RegisterRequest;
import com.example.website.entity.RefreshToken;
import com.example.website.entity.User;
import com.example.website.repository.UserRepository;
import com.example.website.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AppProperties props;
    private final AuthUserCacheService authUserCacheService;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public LoginResponse login(LoginRequest req) {
        String username = normalizeUsername(req.getUsername());
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));
        if (!user.isEnabled()) {
            throw new BadCredentialsException("Invalid username or password");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("Invalid username or password");
        }
        if (passwordEncoder.upgradeEncoding(user.getPassword())) {
            user.setPassword(passwordEncoder.encode(req.getPassword()));
            userRepository.save(user);
        }
        authUserCacheService.evict(user.getId());
        return buildLoginResponse(user);
    }

    @Transactional
    public LoginResponse register(RegisterRequest req) {
        String username = normalizeUsername(req.getUsername());
        String email = normalizeEmail(req.getEmail());
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(409, "Username already exists");
        }
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(409, "Email already exists");
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRole(User.ROLE_USER);
        userRepository.save(user);
        authUserCacheService.evict(user.getId());
        return buildLoginResponse(user);
    }

    /**
     * Exchanges a valid refresh token for a new access JWT and a rotated refresh token.
     * Invalid / expired / reused refresh → business code 401 (mapped to HTTP 401).
     */
    @Transactional
    public LoginResponse refresh(String rawRefreshToken) {
        if (!StringUtils.hasText(rawRefreshToken)) {
            throw unauthorizedRefresh();
        }
        String presented = rawRefreshToken.trim();

        RefreshToken active = refreshTokenService.findActive(presented).orElse(null);
        if (active == null) {
            // Reuse of a revoked token triggers family revoke inside rotate().
            refreshTokenService.rotate(presented);
            throw unauthorizedRefresh();
        }

        User user = loadEnabledUser(active.getUserId());
        String newRefresh = refreshTokenService.rotate(presented);
        if (newRefresh == null) {
            throw unauthorizedRefresh();
        }
        return buildLoginResponseWithRefresh(user, newRefresh);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (StringUtils.hasText(rawRefreshToken)) {
            refreshTokenService.revokeRaw(rawRefreshToken.trim());
        }
    }

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(400, "Old password is incorrect");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setAuthVersion(user.getAuthVersion() + 1);
        userRepository.save(user);
        authUserCacheService.evict(user.getId());
        refreshTokenService.revokeAllForUser(userId);
    }

    private User loadEnabledUser(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isEnabled()) {
            refreshTokenService.revokeAllForUser(userId);
            throw unauthorizedRefresh();
        }
        return user;
    }

    private LoginResponse buildLoginResponse(User user) {
        return buildLoginResponseWithRefresh(user, refreshTokenService.issue(user.getId()));
    }

    private LoginResponse buildLoginResponseWithRefresh(User user, String refreshToken) {
        String token = jwtUtil.generateToken(
                user.getId(),
                user.getUsername(),
                user.getRole(),
                user.getAuthVersion()
        );
        return new LoginResponse(
                token,
                props.getJwt().getPrefix().trim(),
                props.getJwt().getExpireMinutes(),
                refreshToken,
                user.getUsername(),
                user.getRole()
        );
    }

    private static BusinessException unauthorizedRefresh() {
        return new BusinessException(HttpStatus.UNAUTHORIZED.value(), "Invalid refresh token");
    }

    private String normalizeUsername(String username) {
        return username == null ? null : username.trim();
    }

    private String normalizeEmail(String email) {
        if (!StringUtils.hasText(email)) {
            throw new BusinessException(400, "Email is required");
        }
        return email.trim().toLowerCase();
    }
}
