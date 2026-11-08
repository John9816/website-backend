package com.example.website.module.music.api.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class PlaylistImportRequest {

    @NotBlank
    @Size(max = 1000)
    private String url;
}
