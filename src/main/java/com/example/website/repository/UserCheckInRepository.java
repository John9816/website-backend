package com.example.website.repository;

import com.example.website.entity.UserCheckIn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface UserCheckInRepository extends JpaRepository<UserCheckIn, Long> {
    Optional<UserCheckIn> findTopByUserIdOrderByCheckInDateDesc(Long userId);
    boolean existsByUserIdAndCheckInDate(Long userId, LocalDate date);
}
