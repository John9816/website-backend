package com.example.website.module.editor.infrastructure.persistence;

import com.example.website.module.editor.domain.EditorPresence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EditorPresenceRepository extends JpaRepository<EditorPresence, Long> {
}
