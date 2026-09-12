package com.example.website.repository;

import com.example.website.entity.EmailVerificationCode;
import com.example.website.entity.EmailVerificationPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {

    Optional<EmailVerificationCode> findTopByEmailAndPurposeOrderByCreatedAtDesc(
            String email, EmailVerificationPurpose purpose);

    Optional<EmailVerificationCode> findTopByEmailAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(
            String email, EmailVerificationPurpose purpose);

    long countByRequestIpAndCreatedAtAfter(String requestIp, LocalDateTime createdAfter);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EmailVerificationCode code set code.attempts = code.attempts + 1 "
            + "where code.id = :codeId and code.attempts < :maxAttempts")
    int incrementAttempts(@Param("codeId") Long codeId, @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query("delete from EmailVerificationCode code where code.expiresAt < :cutoff "
            + "or (code.usedAt is not null and code.usedAt < :cutoff)")
    int deleteExpiredOrUsedBefore(@Param("cutoff") LocalDateTime cutoff);
}
