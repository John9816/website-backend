package com.example.website.module.editor.infrastructure.persistence;

import com.example.website.module.editor.domain.EditorShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EditorShareRepository extends JpaRepository<EditorShare, String> {

    Optional<EditorShare> findByToken(String token);

    List<EditorShare> findByUserId(Long userId);

    void deleteByToken(String token);
}
