package com.example.website.module.music.api.dto;

import lombok.Data;

@Data
public class ArtistDetailView {
    private String id;
    private MusicSource source;
    private String name;
    private String coverUrl;
    private Integer songCount;   // 歌曲数量
    private Integer albumCount;  // 专辑数量
    private String description;  // 可选
}
