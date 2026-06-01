package com.example.website.dto;

import com.example.website.entity.AiConversation;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class AiConversationView {

    private Long id;
    private String title;
    private String model;
    private String lastMessagePreview;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AiConversationView from(AiConversation conversation) {
        return new AiConversationView(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getModel(),
                conversation.getLastMessagePreview(),
                conversation.getLastMessageAt(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }
}
