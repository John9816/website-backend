package com.example.website.repository;

import com.example.website.entity.ContentArticle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContentArticleRepository extends JpaRepository<ContentArticle, Long> {

    Page<ContentArticle> findByUserIdOrderByUpdatedAtDescIdDesc(Long userId, Pageable pageable);

    Optional<ContentArticle> findByIdAndUserId(Long id, Long userId);
}
