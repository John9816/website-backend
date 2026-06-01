package com.example.website.dto.music;

import com.example.website.entity.MusicShare;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MusicShareView {
    private Long id;
    private MusicSource source;
    private String songId;
    private String name;
    private String artist;
    private String album;
    private String coverUrl;
    private Integer durationSec;
    private String requestedQuality;
    private String token;
    private LocalDateTime expiresAt;
    private Integer viewCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static MusicShareView from(MusicShare entity) {
        if (entity == null) {
            return null;
        }
        MusicShareView view = new MusicShareView();
        view.id = entity.getId();
        view.source = MusicSource.of(entity.getSource());
        view.songId = entity.getSongId();
        view.name = entity.getName();
        view.artist = entity.getArtist();
        view.album = entity.getAlbum();
        view.coverUrl = entity.getCoverUrl();
        view.durationSec = entity.getDurationSec();
        view.requestedQuality = entity.getRequestedQuality();
        view.token = entity.getToken();
        view.expiresAt = entity.getExpiresAt();
        view.viewCount = entity.getViewCount();
        view.createdAt = entity.getCreatedAt();
        view.updatedAt = entity.getUpdatedAt();
        return view;
    }
}
