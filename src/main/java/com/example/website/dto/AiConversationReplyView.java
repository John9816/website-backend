package com.example.website.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AiConversationReplyView {

    private AiConversationView conversation;
    private AiChatMessageView userMessage;
    private AiChatMessageView assistantMessage;
}
