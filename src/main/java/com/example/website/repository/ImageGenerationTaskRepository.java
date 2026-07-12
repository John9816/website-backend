package com.example.website.repository;

import com.example.website.entity.ImageGenerationTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface ImageGenerationTaskRepository extends JpaRepository<ImageGenerationTask, Long> {

    Optional<ImageGenerationTask> findByIdAndUserId(Long id, Long userId);

    Page<ImageGenerationTask> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<ImageGenerationTask> findByUserIdAndStatusInOrderByCreatedAtDesc(
            Long userId, Collection<String> statuses, Pageable pageable);

    long countByUserIdAndStatusIn(Long userId, Collection<String> statuses);
}
