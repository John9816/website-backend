package com.example.website.module.identity.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_check_in", uniqueConstraints = @UniqueConstraint(name = "uk_user_check_in_date", columnNames = {"user_id", "check_in_date"}), indexes = @Index(name = "idx_user_check_in_user_date", columnList = "user_id,check_in_date"))
@Getter
@Setter
@NoArgsConstructor
public class UserCheckIn {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "check_in_date", nullable = false)
    private LocalDate checkInDate;

    @Column(name = "reward_credits", nullable = false)
    private int rewardCredits;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
