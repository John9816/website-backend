package com.example.website.dto.music;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class PlaylistRenameRequest {

    @NotBlank
    @Size(max = 300)
    private String name;
}
