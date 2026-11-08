package com.example.website.module.identity.application;

import com.example.website.config.AppProperties;
import com.example.website.module.identity.domain.RefreshToken;
import com.example.website.module.identity.infrastructure.persistence.RefreshTokenRepository;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final AppProperties props;

    /**
     * Issues a new opaque refresh token for the user and persists only its hash.
     *
     * @return raw token to return to the client (not stored in plain text)
     */
    @Transactional
    public String issue(Long userId) {
        String raw = generateRawToken();
        RefreshToken entity = new RefreshToken();
        entity.setUserId(userId);
        entity.setTokenHash(hash(raw));
        entity.setExpiresAt(LocalDateTime.now().plusDays(props.getJwt().getRefreshExpireDays()));
        refreshTokenRepository.save(entity);
        return raw;
    }

    /**
     * Rotates refresh: revokes the presented token and issues a replacement.
     * If the old token was already revoked (possible reuse), revokes all of the user's tokens.
     *
     * @return user id and raw replacement token, or null when rotation is rejected
     */
    @Transactional
    public RotationResult rotate(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            return null;
        }
        String hash = hash(rawToken.trim());
        RefreshToken existing = refreshTokenRepository.findByTokenHashForUpdate(hash).orElse(null);
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            return null;
        }

        // Reuse of a revoked/rotated token → treat as theft, kill the family.
        if (existing.getRevokedAt() != null) {
            refreshTokenRepository.revokeAllActiveForUser(existing.getUserId(), now);
            return null;
        }
        if (existing.getExpiresAt() == null || !existing.getExpiresAt().isAfter(now)) {
            existing.setRevokedAt(now);
            refreshTokenRepository.save(existing);
            return null;
        }

        String replacementRaw = generateRawToken();
        String replacementHash = hash(replacementRaw);

        existing.setRevokedAt(now);
        existing.setReplacedByHash(replacementHash);
        refreshTokenRepository.save(existing);

        RefreshToken next = new RefreshToken();
        next.setUserId(existing.getUserId());
        next.setTokenHash(replacementHash);
        next.setExpiresAt(now.plusDays(props.getJwt().getRefreshExpireDays()));
        refreshTokenRepository.save(next);
        return new RotationResult(existing.getUserId(), replacementRaw);
    }

    @Transactional
    public void revokeRaw(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            return;
        }
        refreshTokenRepository.findByTokenHash(hash(rawToken.trim())).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(LocalDateTime.now());
                refreshTokenRepository.save(token);
            }
        });
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        if (userId == null) {
            return;
        }
        refreshTokenRepository.revokeAllActiveForUser(userId, LocalDateTime.now());
    }

    @Scheduled(fixedDelayString = "${JWT_REFRESH_CLEANUP_INTERVAL_MS:86400000}",
            initialDelayString = "${JWT_REFRESH_CLEANUP_INITIAL_DELAY_MS:120000}")
    @Transactional
    public void removeOldTokens() {
        long retentionDays = Math.max(1, props.getJwt().getCleanupRetentionDays());
        refreshTokenRepository.deleteExpiredOrRevokedBefore(LocalDateTime.now().minusDays(retentionDays));
    }

    static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return toHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Getter
    @AllArgsConstructor
    public static class RotationResult {
        private final Long userId;
        private final String refreshToken;
    }
}
