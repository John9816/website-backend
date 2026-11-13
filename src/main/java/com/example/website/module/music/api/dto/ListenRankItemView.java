package com.example.website.module.music.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ListenRankItemView {

    private int rank;
    private Long userId;
    private String username;
    private String avatarUrl;
    private long seconds;
}
