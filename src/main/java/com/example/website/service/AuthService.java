package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.config.AppProperties;
import com.example.website.dto.LoginRequest;
import com.example.website.dto.LoginResponse;
import com.example.website.dto.PasswordResetRequest;
import com.example.website.dto.RegisterRequest;
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

import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AppProperties props;
    private final AuthUserCacheService authUserCacheService;
    private final RefreshTokenService refreshTokenService;
    private final EmailVerificationService emailVerificationService;
    private final UserCreditService userCreditService;

    @Transactional
    public LoginResponse login(LoginRequest req, String clientIp) {
        User user = resolveLoginUser(req);
        if (!user.isEnabled() || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw invalidCredentials();
        }
        if (passwordEncoder.upgradeEncoding(user.getPassword())) {
            user.setPassword(passwordEncoder.encode(req.getPassword()));
        }
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(clientIp);
        user.setLoginCount(user.getLoginCount() + 1);
        authUserCacheService.evict(user.getId());
        return buildLoginResponse(user);
    }

    @Transactional
    public LoginResponse register(RegisterRequest req, String clientIp) {
        String username = normalizeUsername(req.getUsername());
        String email = normalizeEmail(req.getEmail());
        boolean usernameExists = userRepository.existsByUsername(username);
        boolean emailExists = userRepository.existsByEmail(email);
        if (usernameExists || emailExists) {
            throw new BusinessException(409, "用户名或邮箱已被使用");
        }
        if (props.getRegistration().getEmailVerification().isRequired()) {
            emailVerificationService.verifyRegistrationCode(email, req.getVerificationCode());
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRole(User.ROLE_USER);
        user.setRegisterIp(clientIp);
        user.setCredits(userCreditService.initialCredits());
        userRepository.save(user);
        authUserCacheService.evict(user.getId());
        return buildLoginResponse(user);
    }

    /**
     * Exchanges a valid refresh token for a new access JWT and a rotated refresh token.
     * Invalid / expired / reused refresh → business code 401 (mapped to HTTP 401).
     */
    public LoginResponse refresh(String rawRefreshToken) {
        if (!StringUtils.hasText(rawRefreshToken)) {
            throw unauthorizedRefresh();
        }
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(rawRefreshToken.trim());
        if (rotation == null) {
            throw unauthorizedRefresh();
        }
        User user = loadEnabledUser(rotation.getUserId());
        return buildLoginResponseWithRefresh(user, rotation.getRefreshToken());
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

    @Transactional
    public void resetPassword(PasswordResetRequest req) {
        String email = normalizeEmail(req.getEmail());
        emailVerificationService.verifyPasswordResetCode(email, req.getVerificationCode());
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new BusinessException(400,
                        "邮箱验证码不存在、已过期或不正确"));
        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        user.setAuthVersion(user.getAuthVersion() + 1);
        userRepository.save(user);
        authUserCacheService.evict(user.getId());
        refreshTokenService.revokeAllForUser(user.getId());
    }

    private User loadEnabledUser(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isEnabled()) {
            refreshTokenService.revokeAllForUser(userId);
            throw unauthorizedRefresh();
        }
        return user;
    }

    private User resolveLoginUser(LoginRequest req) {
        boolean hasUsername = StringUtils.hasText(req.getUsername());
        boolean hasEmail = StringUtils.hasText(req.getEmail());
        if (hasUsername && hasEmail) {
            throw new BusinessException(400, "用户名和邮箱只能填写一个");
        }
        if (!hasUsername && !hasEmail) {
            throw new BusinessException(400, "请输入用户名或邮箱");
        }

        String identifier = (hasEmail ? req.getEmail() : req.getUsername()).trim();
        User user = hasEmail ? userRepository.findByEmailIgnoreCase(identifier).orElse(null)
                : userRepository.findByUsername(identifier).orElse(null);
        if (user == null) {
            throw invalidCredentials();
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

    private static BadCredentialsException invalidCredentials() {
        return new BadCredentialsException("Invalid username or password");
    }

    private String normalizeUsername(String username) {
        return username == null ? null : username.trim();
    }

    private String normalizeEmail(String email) {
        if (!StringUtils.hasText(email)) {
            throw new BusinessException(400, "Email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
