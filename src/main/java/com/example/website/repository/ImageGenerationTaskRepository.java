package com.example.website.repository;

import com.example.website.entity.ImageGenerationTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.time.LocalDateTime;
import java.util.Optional;

public interface ImageGenerationTaskRepository extends JpaRepository<ImageGenerationTask, Long> {

    Optional<ImageGenerationTask> findByIdAndUserId(Long id, Long userId);

    Page<ImageGenerationTask> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<ImageGenerationTask> findByUserIdAndStatusInOrderByCreatedAtDesc(
            Long userId, Collection<String> statuses, Pageable pageable);

    long countByUserIdAndStatusIn(Long userId, Collection<String> statuses);

    @Query("SELECT COUNT(t) FROM ImageGenerationTask t " +
            "WHERE t.userId = :userId AND (t.createdAt >= :since OR t.updatedAt >= :since)")
    long countByUserIdCreatedOrUpdatedSince(@Param("userId") Long userId,
                                            @Param("since") LocalDateTime since);
}
