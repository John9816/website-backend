package com.example.website.repository;

import com.example.website.entity.ContentArticle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ContentArticleRepository extends JpaRepository<ContentArticle, Long> {

    Page<ContentArticle> findByUserIdOrderByUpdatedAtDescIdDesc(Long userId, Pageable pageable);

    @Query(value = "select a.id as id, " +
            "a.title as title, " +
            "a.digest as digest, " +
            "a.coverPrompt as coverPrompt, " +
            "a.coverImageUrl as coverImageUrl, " +
            "a.model as model, " +
            "a.category as category, " +
            "a.layoutTheme as layoutTheme, " +
            "a.imageMode as imageMode, " +
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

    Optional<ContentArticle> findByIdAndUserId(Long id, Long userId);

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

        String getStatus();

        String getWechatMediaId();

        String getWechatPublishId();

        String getWechatUrl();

        String getErrorMessage();

        LocalDateTime getCreatedAt();

        LocalDateTime getUpdatedAt();
    }
}
