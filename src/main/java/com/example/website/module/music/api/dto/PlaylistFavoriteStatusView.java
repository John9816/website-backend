package com.example.website.module.music.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PlaylistFavoriteStatusView {

    private String source;
    private String playlistId;
    private boolean favorite;
    private Long userPlaylistId;
}
