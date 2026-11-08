package com.example.website.module.knowledge.infrastructure.persistence;

import com.example.website.module.knowledge.domain.KbDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {

    Page<KbDocument> findBySpaceIdAndStatusOrderBySortOrderAscIdDesc(Long spaceId, String status, Pageable pageable);

    Page<KbDocument> findBySpaceIdOrderBySortOrderAscIdDesc(Long spaceId, Pageable pageable);

    List<KbDocument> findBySpaceIdAndParentIdAndStatusOrderBySortOrderAsc(Long spaceId, Long parentId, String status);

    Optional<KbDocument> findByIdAndUserId(Long id, Long userId);

    Optional<KbDocument> findBySlug(String slug);

    Optional<KbDocument> findByShareToken(String shareToken);

    @Query("SELECT d FROM KbDocument d WHERE d.spaceId = :spaceId AND d.userId = :userId AND " +
            "(LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(d.content) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<KbDocument> searchInSpace(@Param("spaceId") Long spaceId, @Param("userId") Long userId,
                                    @Param("keyword") String keyword, Pageable pageable);

    long countBySpaceIdAndStatus(Long spaceId, String status);

    long countByUserId(Long userId);

    @Query("SELECT d FROM KbDocument d WHERE d.userId = :userId AND d.status = 'PUBLISHED' " +
            "ORDER BY d.viewCount DESC")
    Page<KbDocument> findPopularDocuments(@Param("userId") Long userId, Pageable pageable);
}
