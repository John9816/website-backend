package com.example.website.module.content.infrastructure.persistence;

import com.example.website.module.content.domain.ContentAutopilotSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface ContentAutopilotSlotRepository extends JpaRepository<ContentAutopilotSlot, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update ContentAutopilotSlot s set s.status = 'RUNNING', s.claimToken = :token, " +
            "s.claimedAt = :now, s.attempts = s.attempts + 1, s.lastError = null " +
            "where s.slotKey = :slotKey and (s.status = 'PENDING' " +
            "or (s.status = 'FAILED' and (s.nextRetryAt is null or s.nextRetryAt <= :now)) " +
            "or (s.status = 'RUNNING' and s.claimedAt < :staleBefore))")
    int claim(@Param("slotKey") String slotKey, @Param("token") String token,
              @Param("now") LocalDateTime now, @Param("staleBefore") LocalDateTime staleBefore);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update ContentAutopilotSlot s set s.status = 'SUCCESS', s.articleId = :articleId, " +
            "s.completedAt = :now, s.nextRetryAt = null, s.lastError = null " +
            "where s.slotKey = :slotKey and s.status = 'RUNNING' and s.claimToken = :token")
    int complete(@Param("slotKey") String slotKey, @Param("token") String token,
                 @Param("articleId") Long articleId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update ContentAutopilotSlot s set s.status = 'FAILED', s.lastError = :message, " +
            "s.articleId = :articleId, s.completedAt = :now, s.nextRetryAt = :retryAt " +
            "where s.slotKey = :slotKey and s.status = 'RUNNING' and s.claimToken = :token")
    int fail(@Param("slotKey") String slotKey, @Param("token") String token,
             @Param("message") String message, @Param("articleId") Long articleId,
             @Param("now") LocalDateTime now, @Param("retryAt") LocalDateTime retryAt);
}
