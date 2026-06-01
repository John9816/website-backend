package com.example.website.dto.music;

import com.example.website.entity.MusicFavorite;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MusicFavoriteView {
    private Long id;
    private MusicSource source;
    private String songId;
    private String name;
    private String artist;
    private String album;
    private String coverUrl;
    private Integer durationSec;
    private LocalDateTime likedAt;

    public static MusicFavoriteView from(MusicFavorite entity) {
        MusicFavoriteView view = new MusicFavoriteView();
        view.id = entity.getId();
        view.source = MusicSource.of(entity.getSource());
        view.songId = entity.getSongId();
        view.name = entity.getName();
        view.artist = entity.getArtist();
        view.album = entity.getAlbum();
        view.coverUrl = entity.getCoverUrl();
        view.durationSec = entity.getDurationSec();
        view.likedAt = entity.getCreatedAt();
        return view;
    }
}
