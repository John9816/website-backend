package com.example.website.module.membership.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "redeem_code", uniqueConstraints = @UniqueConstraint(columnNames = "code_hash"), indexes = {
        @Index(name = "idx_redeem_code_status_created", columnList = "status, created_at"),
        @Index(name = "idx_redeem_code_type_status", columnList = "membership_type, status")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class RedeemCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "code_value", length = 32)
    private String code;

    @Column(name = "code_preview", nullable = false, length = 32)
    private String codePreview;

    @Enumerated(EnumType.STRING)
    @Column(name = "membership_type", nullable = false, length = 20)
    private MembershipType membershipType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RedeemCodeStatus status = RedeemCodeStatus.UNUSED;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "redeemed_by_user_id")
    private Long redeemedByUserId;

    @Column(name = "redeemed_at")
    private LocalDateTime redeemedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
