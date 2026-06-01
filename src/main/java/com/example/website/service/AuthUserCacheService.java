package com.example.website.service;

import com.example.website.config.CacheConfig;
import com.example.website.dto.CachedAuthUser;
import com.example.website.entity.User;
import com.example.website.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Hot-path lookup of the authenticated user identity. JwtAuthFilter calls
 * {@link #load(Long)} on every authenticated request. Without this cache,
 * each call did a SQL round trip to the remote MySQL.
 *
 * Cache TTL is 5 minutes, so user role/username changes (or deletion)
 * propagate within 5 minutes. Mutating endpoints call {@link #evict(Long)}
 * for immediate invalidation.
 */
@Service
@RequiredArgsConstructor
public class AuthUserCacheService {

    private final UserRepository userRepository;

    @Cacheable(value = CacheConfig.CACHE_AUTH_USER, key = "#userId", unless = "#result == null")
    public CachedAuthUser load(Long userId) {
        return userRepository.findById(userId)
                .map(u -> new CachedAuthUser(u.getId(), u.getUsername(), normalizeRole(u.getRole())))
                .orElse(null);
    }

    @CacheEvict(value = CacheConfig.CACHE_AUTH_USER, key = "#userId")
    public void evict(Long userId) {
        // no-op; annotation handles it
    }

    @CacheEvict(value = CacheConfig.CACHE_AUTH_USER, allEntries = true)
    public void evictAll() {
        // no-op; annotation handles it
    }

    private String normalizeRole(String role) {
        if (!StringUtils.hasText(role)) {
            return User.ROLE_USER;
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (User.ROLE_ADMIN.equals(normalized)) {
            return User.ROLE_ADMIN;
        }
        return User.ROLE_USER;
    }
}
