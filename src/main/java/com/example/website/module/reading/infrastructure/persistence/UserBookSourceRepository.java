package com.example.website.module.reading.infrastructure.persistence;

import com.example.website.module.reading.domain.UserBookSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserBookSourceRepository extends JpaRepository<UserBookSource, Long> {
    List<UserBookSource> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<UserBookSource> findByUserIdAndSourceKey(Long userId, String sourceKey);
    Optional<UserBookSource> findBySourceKey(String sourceKey);
}
