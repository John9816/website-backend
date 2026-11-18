package com.example.website.module.editor.infrastructure.persistence;

import com.example.website.module.editor.domain.EditorUserData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EditorUserDataRepository extends JpaRepository<EditorUserData, Long> {

    Optional<EditorUserData> findByUserId(Long userId);
}
