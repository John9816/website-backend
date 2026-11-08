package com.example.website.module.content.infrastructure.persistence;

import com.example.website.module.content.domain.ContentTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContentTemplateRepository extends JpaRepository<ContentTemplate, Long> {

    Page<ContentTemplate> findByUserIdAndIsActiveOrderBySortOrderAscIdDesc(Long userId, Boolean isActive, Pageable pageable);

    Page<ContentTemplate> findByUserIdOrderBySortOrderAscIdDesc(Long userId, Pageable pageable);

    List<ContentTemplate> findByUserIdAndCategoryAndIsActiveOrderBySortOrderAsc(Long userId, String category, Boolean isActive);

    Optional<ContentTemplate> findByIdAndUserId(Long id, Long userId);

    @Modifying
    @Transactional
    @Query("UPDATE ContentTemplate t SET t.usageCount = COALESCE(t.usageCount, 0) + 1 " +
            "WHERE t.id = :templateId AND t.userId = :userId AND t.isActive = true")
    int incrementUsageCount(@Param("templateId") Long templateId, @Param("userId") Long userId);

    @Query("SELECT t FROM ContentTemplate t WHERE t.userId = :userId AND " +
            "(LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(t.description) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(t.category) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<ContentTemplate> searchByKeyword(@Param("userId") Long userId, @Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT DISTINCT t.category FROM ContentTemplate t WHERE t.userId = :userId AND t.category IS NOT NULL ORDER BY t.category")
    List<String> findDistinctCategoriesByUserId(@Param("userId") Long userId);

    long countByUserIdAndIsActive(Long userId, Boolean isActive);
}
