package com.example.website.dto.music;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaylistListView {

    private MusicSource source;
    private String category;
    private String order;
    private int page;
    private int pageSize;
    private Long total;
    private List<PlaylistItem> list;
}
