package com.example.website.module.music.api.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
public class BatchFavoriteStatusRequest {

    @NotBlank
    private String source;

    @NotEmpty
    private List<String> songIds;
}
