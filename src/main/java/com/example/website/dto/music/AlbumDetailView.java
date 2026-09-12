package com.example.website.dto.music;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlbumDetailView {

    private String id;
    private MusicSource source;
    private String name;
    private String coverUrl;
    private String description;
    private String artist;
    private String publishTime;
    private int page;
    private int pageSize;
    private Integer total;
    private List<SongSearchItem> list;
}
