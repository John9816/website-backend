package com.example.website.dto;

import lombok.Data;

@Data
public class ImageEditRequest {

    private String prompt;
    private Integer n;
    private String size;
    private byte[] imageBytes;
    private String imageFilename;
    private String imageContentType;
    private byte[] maskBytes;
    private String maskFilename;
    private String maskContentType;
}
