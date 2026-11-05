package com.example.website.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EntityListeners;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "content_autopilot_slot")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class ContentAutopilotSlot {

    @Id
    @Column(name = "slot_key", length = 80)
    private String slotKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "slot_at", nullable = false)
    private LocalDateTime slotAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "claim_token", length = 40)
    private String claimToken;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "article_id")
    private Long articleId;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
