package com.example.website.dto.music;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaylistDetailView {

    private String id;
    private MusicSource source;
    private String name;
    private String coverUrl;
    private String description;
    private String creatorName;
    private Long playCount;
    private String updateTime;
    private int page;
    private int pageSize;
    private Integer total;
    private List<SongSearchItem> list;
}
