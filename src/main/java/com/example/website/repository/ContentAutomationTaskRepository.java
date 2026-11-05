package com.example.website.repository;

import com.example.website.entity.ContentAutomationTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ContentAutomationTaskRepository extends JpaRepository<ContentAutomationTask, Long> {

    Page<ContentAutomationTask> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    Page<ContentAutomationTask> findByUserIdAndIsActiveOrderByIdDesc(Long userId, Boolean isActive, Pageable pageable);

    Optional<ContentAutomationTask> findByIdAndUserId(Long id, Long userId);

    List<ContentAutomationTask> findByIsActiveAndNextRunAtBeforeOrderByNextRunAtAsc(Boolean isActive, LocalDateTime now);

    List<ContentAutomationTask> findByIsActiveAndNextRunAtLessThanEqualOrderByNextRunAtAsc(Boolean isActive, LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update ContentAutomationTask t set t.status = 'RUNNING', t.lastError = null, t.runStartedAt = :now " +
            "where t.id = :taskId and (t.status <> 'RUNNING' or t.runStartedAt is null or t.runStartedAt < :staleBefore)")
    int claimTask(@Param("taskId") Long taskId, @Param("now") LocalDateTime now,
                  @Param("staleBefore") LocalDateTime staleBefore);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update ContentAutomationTask t set t.status = 'RUNNING', t.lastError = null, t.runStartedAt = :now " +
            "where t.id = :taskId and (t.status <> 'RUNNING' or t.runStartedAt is null or t.runStartedAt < :staleBefore) and t.isActive = true " +
            "and t.nextRunAt is not null and t.nextRunAt <= :now")
    int claimDueTask(@Param("taskId") Long taskId, @Param("now") LocalDateTime now,
                     @Param("staleBefore") LocalDateTime staleBefore);

    @Query("SELECT t FROM ContentAutomationTask t WHERE t.userId = :userId AND " +
            "(LOWER(t.taskName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(t.taskType) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<ContentAutomationTask> searchByKeyword(@Param("userId") Long userId, @Param("keyword") String keyword, Pageable pageable);

    long countByUserIdAndIsActive(Long userId, Boolean isActive);

    long countByUserId(Long userId);

    long countByUserIdAndStatus(Long userId, String status);
}
