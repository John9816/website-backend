package com.example.website.module.music.api.dto;

import com.example.website.module.music.domain.MusicShare;
import lombok.Data;

/**
 * Lightweight, read-only snapshot of a public share used by the edge HTML renderer to
 * build social preview metadata. Unlike {@link MusicPublicShareView} this never resolves
 * an upstream play URL, so a crawler hitting /music/share/:token does not trigger any
 * third-party music API call or bump the view counter.
 */
@Data
public class MusicShareMetaView {

    private String token;
    private MusicSource source;
    private String songId;
    private String name;
    private String artist;
    private String album;
    private String coverUrl;
    private Integer durationSec;

    public static MusicShareMetaView from(MusicShare share) {
        MusicShareMetaView view = new MusicShareMetaView();
        view.token = share.getToken();
        view.source = MusicSource.of(share.getSource());
        view.songId = share.getSongId();
        view.name = share.getName();
        view.artist = share.getArtist();
        view.album = share.getAlbum();
        view.coverUrl = share.getCoverUrl();
        view.durationSec = share.getDurationSec();
        return view;
    }
}
