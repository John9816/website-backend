package com.example.website.dto.music;

import com.example.website.entity.MusicPlayHistory;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MusicHistoryView {
    private Long id;
    private MusicSource source;
    private String songId;
    private String name;
    private String artist;
    private String album;
    private String coverUrl;
    private Integer durationSec;
    private LocalDateTime playedAt;
    private LocalDateTime createdAt;

    public static MusicHistoryView from(MusicPlayHistory entity) {
        MusicHistoryView view = new MusicHistoryView();
        view.id = entity.getId();
        view.source = MusicSource.of(entity.getSource());
        view.songId = entity.getSongId();
        view.name = entity.getName();
        view.artist = entity.getArtist();
        view.album = entity.getAlbum();
        view.coverUrl = entity.getCoverUrl();
        view.durationSec = entity.getDurationSec();
        view.playedAt = entity.getPlayedAt();
        view.createdAt = entity.getCreatedAt();
        return view;
    }
}
