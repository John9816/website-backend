package com.example.website.dto.book;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;

@Getter
@Setter
public class BookSourceImportRequest {
    @NotBlank
    private String definition;
}
