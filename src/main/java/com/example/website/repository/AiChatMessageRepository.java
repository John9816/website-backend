package com.example.website.repository;

import com.example.website.entity.AiChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

    Page<AiChatMessage> findByConversationIdOrderByIdAsc(Long conversationId, Pageable pageable);

    List<AiChatMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    long countByConversationId(Long conversationId);

    void deleteByConversationId(Long conversationId);

    @Query("select m from AiChatMessage m where m.conversationId = :conversationId " +
            "order by m.id desc")
    List<AiChatMessage> findRecentByConversationId(@Param("conversationId") Long conversationId,
                                                   Pageable pageable);

    @Query("select m.id as id, m.role as role, m.content as content, m.model as model, " +
            "m.audioMimeType as audioMimeType, m.audioModel as audioModel, " +
            "m.audioSourceUrl as audioSourceUrl, " +
            "case when m.audioData is not null and length(m.audioData) > 0 then true else false end as hasAudioData, " +
            "m.finishReason as finishReason, m.promptTokens as promptTokens, " +
            "m.completionTokens as completionTokens, m.totalTokens as totalTokens, " +
            "m.createdAt as createdAt " +
            "from AiChatMessage m where m.conversationId = :conversationId order by m.id asc")
    Page<AiChatMessageSummary> findSummariesByConversationId(@Param("conversationId") Long conversationId,
                                                              Pageable pageable);

    interface AiChatMessageSummary {
        Long getId();
        String getRole();
        String getContent();
        String getModel();
        String getAudioMimeType();
        String getAudioModel();
        String getAudioSourceUrl();
        Boolean getHasAudioData();
        String getFinishReason();
        Integer getPromptTokens();
        Integer getCompletionTokens();
        Integer getTotalTokens();
        LocalDateTime getCreatedAt();
    }
}
