package com.example.website.repository;

import com.example.website.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Query("select count(t) from RefreshToken t " +
            "where t.userId = :userId and t.revokedAt is null and t.expiresAt > :now")
    long countActiveForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now " +
            "where t.userId = :userId and t.revokedAt is null")
    int revokeAllActiveForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RefreshToken t where t.expiresAt < :before or t.revokedAt < :before")
    int deleteExpiredOrRevokedBefore(@Param("before") LocalDateTime before);
}
