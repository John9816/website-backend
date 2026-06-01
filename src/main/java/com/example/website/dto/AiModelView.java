package com.example.website.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AiModelView {

    private String model;
    private boolean defaultModel;
    private List<String> capabilities;
}
