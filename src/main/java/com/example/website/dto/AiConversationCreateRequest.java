package com.example.website.dto;

import lombok.Data;

import javax.validation.constraints.Size;

@Data
public class AiConversationCreateRequest {

    @Size(max = 120, message = "title length must be <= 120")
    private String title;

    @Size(max = 100, message = "model length must be <= 100")
    private String model;
}
