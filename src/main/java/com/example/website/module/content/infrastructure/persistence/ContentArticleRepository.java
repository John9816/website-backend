package com.example.website.module.content.infrastructure.persistence;

import com.example.website.module.content.domain.ContentArticle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import javax.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ContentArticleRepository extends JpaRepository<ContentArticle, Long> {

    Page<ContentArticle> findByUserIdOrderByUpdatedAtDescIdDesc(Long userId, Pageable pageable);

    long countByUserId(Long userId);

    /** Autopilot slot guard: how many articles this user already produced at/after a given instant. */
    long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime createdAt);

    /** Topic dedup: recent titles by this user since the given instant, newest first. */
    @Query("select a.title from ContentArticle a " +
            "where a.userId = :userId and a.createdAt >= :since " +
            "order by a.createdAt desc")
    List<String> findRecentTitles(@Param("userId") Long userId,
                                  @Param("since") LocalDateTime since,
                                  Pageable pageable);

    @Query(value = "select a.id as id, " +
            "a.title as title, " +
            "a.digest as digest, " +
            "a.coverPrompt as coverPrompt, " +
            "a.coverImageUrl as coverImageUrl, " +
            "a.model as model, " +
            "a.category as category, " +
            "a.layoutTheme as layoutTheme, " +
            "a.imageMode as imageMode, " +
            "a.qualityScore as qualityScore, " +
            "a.status as status, " +
            "a.wechatMediaId as wechatMediaId, " +
            "a.wechatPublishId as wechatPublishId, " +
            "a.wechatUrl as wechatUrl, " +
            "a.errorMessage as errorMessage, " +
            "a.createdAt as createdAt, " +
            "a.updatedAt as updatedAt " +
            "from ContentArticle a " +
            "where a.userId = :userId " +
            "order by a.updatedAt desc, a.id desc",
            countQuery = "select count(a.id) from ContentArticle a where a.userId = :userId")
    Page<ContentArticleSummary> findSummariesByUserId(@Param("userId") Long userId, Pageable pageable);

    /**
     * Site-wide projection used only by the admin content console.  Keep this
     * separate from the user-scoped query above so a caller cannot accidentally
     * broaden an ordinary user's article listing.
     */
    @Query(value = "select a.id as id, " +
            "a.title as title, " +
            "a.digest as digest, " +
            "a.coverPrompt as coverPrompt, " +
            "a.coverImageUrl as coverImageUrl, " +
            "a.model as model, " +
            "a.category as category, " +
            "a.layoutTheme as layoutTheme, " +
            "a.imageMode as imageMode, " +
            "a.qualityScore as qualityScore, " +
            "a.status as status, " +
            "a.wechatMediaId as wechatMediaId, " +
            "a.wechatPublishId as wechatPublishId, " +
            "a.wechatUrl as wechatUrl, " +
            "a.errorMessage as errorMessage, " +
            "a.createdAt as createdAt, " +
            "a.updatedAt as updatedAt " +
            "from ContentArticle a " +
            "order by a.updatedAt desc, a.id desc",
            countQuery = "select count(a.id) from ContentArticle a")
    Page<ContentArticleSummary> findAllSummariesForAdmin(Pageable pageable);

    Optional<ContentArticle> findByIdAndUserId(Long id, Long userId);

    /** Explicit unscoped read for the admin content console. */
    @Query("select a from ContentArticle a where a.id = :id")
    Optional<ContentArticle> findByIdForAdmin(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ContentArticle a where a.id = :id and a.userId = :userId")
    Optional<ContentArticle> findLockedByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** Explicit site-wide lock for admin mutations and WeChat delivery retries. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ContentArticle a where a.id = :id")
    Optional<ContentArticle> findLockedByIdForAdmin(@Param("id") Long id);

    List<ContentArticle> findTop50ByStatusAndWechatPublishIdIsNotNullOrderByUpdatedAtAsc(String status);

    interface ContentArticleSummary {
        Long getId();

        String getTitle();

        String getDigest();

        String getCoverPrompt();

        String getCoverImageUrl();

        String getModel();

        String getCategory();

        String getLayoutTheme();

        String getImageMode();

        Integer getQualityScore();

        String getStatus();

        String getWechatMediaId();

        String getWechatPublishId();

        String getWechatUrl();

        String getErrorMessage();

        LocalDateTime getCreatedAt();

        LocalDateTime getUpdatedAt();
    }
}
