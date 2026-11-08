package com.example.website.module.images.api.dto;

import lombok.Data;

@Data
public class ImageEditRequest {

    private String prompt;
    private String model;
    private Integer n;
    private String size;
    private byte[] imageBytes;
    private String imageFilename;
    private String imageContentType;
    private byte[] maskBytes;
    private String maskFilename;
    private String maskContentType;
}
