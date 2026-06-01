package com.example.website.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerateResponse {

    private String model;
    private String imageUrl;
    private String content;
    private Map<String, Object> raw;
}
