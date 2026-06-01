package com.example.website.dto;

import com.example.website.entity.AiChatMessage;
import com.example.website.repository.AiChatMessageRepository.AiChatMessageSummary;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class AiChatMessageView {

    private Long id;
    private String role;
    private String content;
    private String model;
    private boolean audioAvailable;
    private String audioMimeType;
    private String audioModel;
    private String audioUrl;
    private String finishReason;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private LocalDateTime createdAt;

    public static AiChatMessageView from(AiChatMessage message) {
        return new AiChatMessageView(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getModel(),
                hasText(message.getAudioData()) || hasText(message.getAudioSourceUrl()),
                message.getAudioMimeType(),
                message.getAudioModel(),
                audioUrl(message),
                message.getFinishReason(),
                message.getPromptTokens(),
                message.getCompletionTokens(),
                message.getTotalTokens(),
                message.getCreatedAt()
        );
    }

    public static AiChatMessageView from(AiChatMessageSummary s) {
        boolean hasAudio = Boolean.TRUE.equals(s.getHasAudioData()) || hasText(s.getAudioSourceUrl());
        String audioUrl;
        if (hasText(s.getAudioSourceUrl())) {
            audioUrl = s.getAudioSourceUrl();
        } else if (Boolean.TRUE.equals(s.getHasAudioData())) {
            audioUrl = "/api/user/ai/messages/" + s.getId() + "/audio";
        } else {
            audioUrl = null;
        }
        return new AiChatMessageView(
                s.getId(),
                s.getRole(),
                s.getContent(),
                s.getModel(),
                hasAudio,
                s.getAudioMimeType(),
                s.getAudioModel(),
                audioUrl,
                s.getFinishReason(),
                s.getPromptTokens(),
                s.getCompletionTokens(),
                s.getTotalTokens(),
                s.getCreatedAt()
        );
    }

    private static String audioUrl(AiChatMessage message) {
        if (hasText(message.getAudioSourceUrl())) {
            return message.getAudioSourceUrl();
        }
        if (hasText(message.getAudioData())) {
            return "/api/user/ai/messages/" + message.getId() + "/audio";
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
