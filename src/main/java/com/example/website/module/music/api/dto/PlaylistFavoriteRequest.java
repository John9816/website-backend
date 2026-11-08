package com.example.website.module.music.api.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class PlaylistFavoriteRequest {

    @NotBlank(message = "source is required")
    @Size(max = 20)
    private String source;

    @NotBlank(message = "playlistId is required")
    @Size(max = 100)
    private String playlistId;
}
