package com.example.website.module.ai.infrastructure.persistence;

import com.example.website.module.ai.domain.AiConversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {

    Page<AiConversation> findByUserIdOrderByLastMessageAtDescIdDesc(Long userId, Pageable pageable);

    Optional<AiConversation> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);
}
