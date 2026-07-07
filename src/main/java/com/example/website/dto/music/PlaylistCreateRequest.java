package com.example.website.dto.music;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class PlaylistCreateRequest {

    @NotBlank
    @Size(max = 300)
    private String name;

    @Size(max = 2000)
    private String description;

    @Size(max = 1000)
    private String coverUrl;
}
