package com.example.website.dto.music;

import com.example.website.entity.MusicShare;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MusicPublicShareView {
    private String token;
    private MusicSource source;
    private String songId;
    private String name;
    private String artist;
    private String album;
    private String coverUrl;
    private Integer durationSec;
    private String requestedQuality;
    private LocalDateTime expiresAt;
    private Integer viewCount;
    private Boolean playable;
    private String playError;
    private PlayInfo playInfo;

    public static MusicPublicShareView from(MusicShare share, PlayInfo playInfo, String playError) {
        MusicPublicShareView view = new MusicPublicShareView();
        view.token = share.getToken();
        view.source = MusicSource.of(share.getSource());
        view.songId = share.getSongId();
        view.name = share.getName();
        view.artist = share.getArtist();
        view.album = share.getAlbum();
        view.coverUrl = share.getCoverUrl();
        view.durationSec = share.getDurationSec();
        view.requestedQuality = share.getRequestedQuality();
        view.expiresAt = share.getExpiresAt();
        view.viewCount = share.getViewCount();
        view.playInfo = playInfo;
        view.playable = playInfo != null && hasText(playInfo.getPlayUrl());
        view.playError = playError;
        return view;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
