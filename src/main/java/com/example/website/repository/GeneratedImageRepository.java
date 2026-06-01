package com.example.website.repository;

import com.example.website.entity.GeneratedImage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface GeneratedImageRepository extends JpaRepository<GeneratedImage, Long> {

    Page<GeneratedImage> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    Optional<GeneratedImage> findByIdAndUserId(Long id, Long userId);

    Page<GeneratedImage> findByIsSharedTrueOrderByCreatedAtDescIdDesc(Pageable pageable);

    @Query("SELECT COUNT(g) FROM GeneratedImage g WHERE g.userId = :userId AND g.createdAt >= :since")
    long countByUserIdSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    @Query("select g.id as id, g.userId as userId, g.prompt as prompt, g.imageUrl as imageUrl, " +
            "g.model as model, g.size as size, g.isShared as isShared, g.createdAt as createdAt " +
            "from GeneratedImage g where g.userId = :userId " +
            "order by g.id desc")
    Page<GeneratedImageSummary> findSummariesByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("select g.id as id, g.userId as userId, g.prompt as prompt, g.imageUrl as imageUrl, " +
            "g.model as model, g.size as size, g.isShared as isShared, g.createdAt as createdAt " +
            "from GeneratedImage g where g.isShared = true " +
            "order by g.createdAt desc, g.id desc")
    Page<GeneratedImageSummary> findSharedSummaries(Pageable pageable);

    interface GeneratedImageSummary {
        Long getId();
        Long getUserId();
        String getPrompt();
        String getImageUrl();
        String getModel();
        String getSize();
        Boolean getIsShared();
        LocalDateTime getCreatedAt();
    }
}
