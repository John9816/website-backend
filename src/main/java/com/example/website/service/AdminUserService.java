package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.AdminUserCreateRequest;
import com.example.website.dto.AdminUserUpdateRequest;
import com.example.website.dto.AdminUserView;
import com.example.website.dto.PageView;
import com.example.website.entity.User;
import com.example.website.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthUserCacheService authUserCacheService;

    @Transactional(readOnly = true)
    public PageView<AdminUserView> list(String keyword, String role, Boolean enabled, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Specification<User> spec = Specification.where(null);

        if (StringUtils.hasText(keyword)) {
            String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("username")), pattern),
                    cb.like(cb.lower(root.get("email")), pattern)
            ));
        }
        if (StringUtils.hasText(role)) {
            String normalizedRole = normalizeRole(role);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("role"), normalizedRole));
        }
        if (enabled != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("enabled"), enabled));
        }

        Page<User> users = userRepository.findAll(
                spec,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id"))
        );
        return PageView.from(users, AdminUserView::from);
    }

    @Transactional
    public AdminUserView create(AdminUserCreateRequest request) {
        String username = request.getUsername().trim();
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(409, "Username already exists");
        }
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(409, "Email already exists");
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(normalizeRole(request.getRole()));
        user.setEnabled(true);
        return AdminUserView.from(userRepository.save(user));
    }

    @Transactional
    public AdminUserView update(Long actorId, Long userId, AdminUserUpdateRequest request) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));
        String nextRole = normalizeRole(request.getRole());
        boolean nextEnabled = Boolean.TRUE.equals(request.getEnabled());

        if (actorId.equals(userId) && (!nextEnabled || !User.ROLE_ADMIN.equals(nextRole))) {
            throw new BusinessException(400, "You cannot disable or demote your own administrator account");
        }

        boolean removesActiveAdmin = User.ROLE_ADMIN.equals(target.getRole())
                && target.isEnabled()
                && (!User.ROLE_ADMIN.equals(nextRole) || !nextEnabled);
        if (removesActiveAdmin) {
            List<User> admins = userRepository.findAdminsForUpdate();
            long activeAdmins = admins.stream().filter(User::isEnabled).count();
            if (activeAdmins <= 1) {
                throw new BusinessException(400, "At least one enabled administrator is required");
            }
        }

        boolean authChanged = !nextRole.equals(target.getRole()) || nextEnabled != target.isEnabled();
        target.setRole(nextRole);
        target.setEnabled(nextEnabled);
        if (authChanged) {
            target.setAuthVersion(target.getAuthVersion() + 1);
        }
        User saved = userRepository.save(target);
        authUserCacheService.evict(saved.getId());
        return AdminUserView.from(saved);
    }

    @Transactional
    public void resetPassword(Long userId, String password) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));
        target.setPassword(passwordEncoder.encode(password));
        target.setAuthVersion(target.getAuthVersion() + 1);
        userRepository.save(target);
        authUserCacheService.evict(target.getId());
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!User.ROLE_ADMIN.equals(normalized) && !User.ROLE_USER.equals(normalized)) {
            throw new BusinessException(400, "role must be ADMIN or USER");
        }
        return normalized;
    }
}
