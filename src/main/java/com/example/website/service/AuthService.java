package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.config.AppProperties;
import com.example.website.dto.LoginRequest;
import com.example.website.dto.LoginResponse;
import com.example.website.dto.RegisterRequest;
import com.example.website.entity.User;
import com.example.website.repository.UserRepository;
import com.example.website.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AppProperties props;
    private final AuthUserCacheService authUserCacheService;

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
    }

    private LoginResponse buildLoginResponse(User user) {
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
                user.getUsername(),
                user.getRole()
        );
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
