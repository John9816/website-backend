package com.example.website.module.identity.infrastructure.persistence;

import com.example.website.module.identity.domain.UserCheckIn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface UserCheckInRepository extends JpaRepository<UserCheckIn, Long> {
    Optional<UserCheckIn> findTopByUserIdOrderByCheckInDateDesc(Long userId);
    boolean existsByUserIdAndCheckInDate(Long userId, LocalDate date);
}
