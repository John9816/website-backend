package com.example.website.dto;

import lombok.Data;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.Size;

@Data
public class AiConversationSendRequest {

    @Size(max = 8000, message = "content length must be <= 8000")
    private String content;

    @Size(max = 100, message = "model length must be <= 100")
    private String model;

    private boolean responseAudio;

    @Size(max = 100, message = "ttsModel length must be <= 100")
    private String ttsModel;

    @Size(max = 20, message = "ttsFormat length must be <= 20")
    private String ttsFormat;

    private String ttsVoice;

    @Size(max = 2000, message = "ttsPrompt length must be <= 2000")
    private String ttsPrompt;

    private String inputAudioData;

    @AssertTrue(message = "content or inputAudioData is required")
    public boolean isContentOrAudioPresent() {
        return hasText(content) || hasText(inputAudioData);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
