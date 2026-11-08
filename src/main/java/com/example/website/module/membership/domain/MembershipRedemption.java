package com.example.website.module.membership.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "membership_redemption", uniqueConstraints = @UniqueConstraint(columnNames = "redeem_code_id"), indexes = {
        @Index(name = "idx_membership_redemption_user_time", columnList = "user_id, redeemed_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class MembershipRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "redeem_code_id", nullable = false)
    private Long redeemCodeId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "membership_type", nullable = false, length = 20)
    private MembershipType membershipType;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_membership_type", length = 20)
    private MembershipType previousMembershipType;

    @Column(name = "previous_expires_at")
    private LocalDateTime previousExpiresAt;

    @Column(name = "new_expires_at")
    private LocalDateTime newExpiresAt;

    @CreatedDate
    @Column(name = "redeemed_at", nullable = false, updatable = false)
    private LocalDateTime redeemedAt;
}
