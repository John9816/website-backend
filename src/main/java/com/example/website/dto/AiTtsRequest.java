package com.example.website.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class AiTtsRequest {

    @NotBlank(message = "text is required")
    @Size(max = 8000, message = "text length must be <= 8000")
    private String text;

    @Size(max = 100, message = "ttsModel length must be <= 100")
    private String ttsModel;

    private String ttsVoice;

    @Size(max = 20, message = "ttsFormat length must be <= 20")
    private String ttsFormat;

    @Size(max = 2000, message = "ttsPrompt length must be <= 2000")
    private String ttsPrompt;
}
