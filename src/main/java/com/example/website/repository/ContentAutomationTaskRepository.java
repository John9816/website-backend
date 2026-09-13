package com.example.website.repository;

import com.example.website.entity.ContentAutomationTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ContentAutomationTaskRepository extends JpaRepository<ContentAutomationTask, Long> {

    Page<ContentAutomationTask> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    Page<ContentAutomationTask> findByUserIdAndIsActiveOrderByIdDesc(Long userId, Boolean isActive, Pageable pageable);

    Optional<ContentAutomationTask> findByIdAndUserId(Long id, Long userId);

    List<ContentAutomationTask> findByIsActiveAndNextRunAtBeforeOrderByNextRunAtAsc(Boolean isActive, LocalDateTime now);

    @Query("SELECT t FROM ContentAutomationTask t WHERE t.userId = :userId AND " +
            "(LOWER(t.taskName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(t.taskType) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<ContentAutomationTask> searchByKeyword(@Param("userId") Long userId, @Param("keyword") String keyword, Pageable pageable);

    long countByUserIdAndIsActive(Long userId, Boolean isActive);

    long countByUserIdAndStatus(Long userId, String status);
}
