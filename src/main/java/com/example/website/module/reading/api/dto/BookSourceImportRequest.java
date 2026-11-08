package com.example.website.module.reading.api.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;

@Getter
@Setter
public class BookSourceImportRequest {
    @NotBlank
    private String definition;
}
