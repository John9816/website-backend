package com.example.website.dto.music;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class MusicFavoriteRequest {

    @NotBlank(message = "source is required")
    @Size(max = 20)
    private String source;

    @NotBlank(message = "songId is required")
    @Size(max = 100)
    private String songId;

    @NotBlank(message = "name is required")
    @Size(max = 300)
    private String name;

    @Size(max = 300)
    private String artist;

    @Size(max = 300)
    private String album;

    @Size(max = 1000)
    private String coverUrl;

    private Integer durationSec;
}
