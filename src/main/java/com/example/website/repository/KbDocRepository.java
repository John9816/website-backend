package com.example.website.repository;

import com.example.website.entity.KbDoc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KbDocRepository extends JpaRepository<KbDoc, Long> {

    Optional<KbDoc> findByIdAndUserId(Long id, Long userId);

    List<KbDoc> findByParentIdAndUserId(Long parentId, Long userId);

    long countBySpaceIdAndUserId(Long spaceId, Long userId);

    @Query("select d.id as id, d.spaceId as spaceId, d.parentId as parentId, d.userId as userId, " +
            "d.title as title, d.summary as summary, d.status as status, d.sortOrder as sortOrder, " +
            "d.versionNo as versionNo, d.createdAt as createdAt, d.updatedAt as updatedAt " +
            "from KbDoc d where d.spaceId = :spaceId and d.userId = :userId " +
            "order by d.sortOrder asc, d.id asc")
    List<KbDocSummary> findSummariesBySpaceAndUser(@Param("spaceId") Long spaceId,
                                                   @Param("userId") Long userId);

    @Query("select d.id as id, d.spaceId as spaceId, d.parentId as parentId, d.userId as userId, " +
            "d.title as title, d.summary as summary, d.status as status, d.sortOrder as sortOrder, " +
            "d.versionNo as versionNo, d.createdAt as createdAt, d.updatedAt as updatedAt " +
            "from KbDoc d where d.userId = :userId " +
            "and (:spaceId is null or d.spaceId = :spaceId) " +
            "and (:hasParent = false or d.parentId = :parentId) " +
            "and (:hasIdFilter = false or d.id in :ids) " +
            "and (:keyword is null or lower(d.title) like :keyword or lower(coalesce(d.summary,'')) like :keyword) " +
            "order by d.updatedAt desc, d.id desc")
    Page<KbDocSummary> search(@Param("userId") Long userId,
                              @Param("spaceId") Long spaceId,
                              @Param("hasParent") boolean hasParent,
                              @Param("parentId") Long parentId,
                              @Param("hasIdFilter") boolean hasIdFilter,
                              @Param("ids") Collection<Long> ids,
                              @Param("keyword") String keyword,
                              Pageable pageable);

    @Modifying
    @Query("update KbDoc d set d.parentId = :newParentId, d.updatedAt = :now " +
            "where d.parentId = :oldParentId and d.userId = :userId")
    int repointChildren(@Param("oldParentId") Long oldParentId,
                        @Param("newParentId") Long newParentId,
                        @Param("userId") Long userId,
                        @Param("now") LocalDateTime now);

    interface KbDocSummary {
        Long getId();
        Long getSpaceId();
        Long getParentId();
        Long getUserId();
        String getTitle();
        String getSummary();
        String getStatus();
        Integer getSortOrder();
        Integer getVersionNo();
        LocalDateTime getCreatedAt();
        LocalDateTime getUpdatedAt();
    }
}
