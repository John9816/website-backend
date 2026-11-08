package com.example.website.module.knowledge.infrastructure.persistence;

import com.example.website.module.knowledge.domain.KbDocumentVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KbDocumentVersionRepository extends JpaRepository<KbDocumentVersion, Long> {

    Page<KbDocumentVersion> findByDocumentIdOrderByVersionNumberDesc(Long documentId, Pageable pageable);

    List<KbDocumentVersion> findTop10ByDocumentIdOrderByVersionNumberDesc(Long documentId);

    KbDocumentVersion findTopByDocumentIdOrderByVersionNumberDesc(Long documentId);

    int countByDocumentId(Long documentId);
}
