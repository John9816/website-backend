package com.example.website.repository;

import com.example.website.entity.KbDocVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface KbDocVersionRepository extends JpaRepository<KbDocVersion, Long> {

    Page<KbDocVersion> findByDocIdOrderByVersionNoDesc(Long docId, Pageable pageable);

    Optional<KbDocVersion> findByIdAndDocId(Long id, Long docId);

    @Query("select v.id as id, v.docId as docId, v.versionNo as versionNo, v.title as title, " +
            "v.summary as summary, v.editorUserId as editorUserId, v.changeNote as changeNote, " +
            "v.createdAt as createdAt from KbDocVersion v where v.docId = :docId " +
            "order by v.versionNo desc")
    Page<KbDocVersionSummary> findSummariesByDocId(@Param("docId") Long docId, Pageable pageable);

    interface KbDocVersionSummary {
        Long getId();
        Long getDocId();
        Integer getVersionNo();
        String getTitle();
        String getSummary();
        Long getEditorUserId();
        String getChangeNote();
        LocalDateTime getCreatedAt();
    }
}
