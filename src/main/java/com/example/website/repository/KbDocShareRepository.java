package com.example.website.repository;

import com.example.website.entity.KbDocShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface KbDocShareRepository extends JpaRepository<KbDocShare, Long> {

    Optional<KbDocShare> findByDocId(Long docId);

    Optional<KbDocShare> findByToken(String token);

    boolean existsByToken(String token);

    @Modifying
    @Query("update KbDocShare s set s.viewCount = s.viewCount + 1 where s.id = :id")
    int incrementViewCount(@Param("id") Long id);

    @Query("select s.docId as docId, s.token as token, d.parentId as parentId, d.title as title, " +
            "d.summary as summary, d.sortOrder as sortOrder, d.updatedAt as updatedAt " +
            "from KbDocShare s join KbDoc d on d.id = s.docId " +
            "where s.userId = :userId and s.enabled = true " +
            "and (s.expiresAt is null or s.expiresAt >= :now) " +
            "order by case when s.docId = :currentDocId then 0 else 1 end, d.updatedAt desc, d.id desc")
    List<PublicDocItem> findActivePublicDocItemsByUserId(@Param("userId") Long userId,
                                                         @Param("currentDocId") Long currentDocId,
                                                         @Param("now") LocalDateTime now);

    interface PublicDocItem {
        Long getDocId();
        String getToken();
        Long getParentId();
        String getTitle();
        String getSummary();
        Integer getSortOrder();
        LocalDateTime getUpdatedAt();
    }
}
