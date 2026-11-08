package com.example.website.module.music.api.dto;

import com.example.website.module.music.domain.MusicFavorite;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MusicFavoriteStatusView {

    private String source;
    private String songId;
    private boolean liked;
    private Long favoriteId;

    public static MusicFavoriteStatusView liked(MusicFavorite e) {
        return new MusicFavoriteStatusView(e.getSource(), e.getSongId(), true, e.getId());
    }

    public static MusicFavoriteStatusView notLiked(String source, String songId) {
        return new MusicFavoriteStatusView(source, songId, false, null);
    }
}
