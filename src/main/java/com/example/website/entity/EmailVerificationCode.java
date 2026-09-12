package com.example.website.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "email_verification_code", indexes = {
        @Index(name = "idx_email_verification_email_created", columnList = "email, created_at"),
        @Index(name = "idx_email_verification_email_purpose_created", columnList = "email, purpose, created_at"),
        @Index(name = "idx_email_verification_ip_created", columnList = "request_ip, created_at"),
        @Index(name = "idx_email_verification_expires", columnList = "expires_at"),
        @Index(name = "idx_email_verification_used", columnList = "used_at")
})
@Getter
@Setter
@NoArgsConstructor
public class EmailVerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private EmailVerificationPurpose purpose = EmailVerificationPurpose.REGISTER;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "request_ip", length = 45)
    private String requestIp;
}
