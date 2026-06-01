package com.example.website.dto.music;

import com.example.website.entity.UserPlaylistItem;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPlaylistItemView {

    private Long id;
    private String source;
    private String songId;
    private String name;
    private String artist;
    private String album;
    private String coverUrl;
    private Integer durationSec;
    private Integer sortOrder;

    public static UserPlaylistItemView from(UserPlaylistItem e) {
        return new UserPlaylistItemView(
                e.getId(),
                e.getSource(),
                e.getSongId(),
                e.getName(),
                e.getArtist(),
                e.getAlbum(),
                e.getCoverUrl(),
                e.getDurationSec(),
                e.getSortOrder()
        );
    }
}
