package com.example.website.module.music.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlbumSummaryItem {

    private String id;
    private MusicSource source;
    private String name;
    private String artist;
    private String artistId;
    private List<String> artistIds = Collections.emptyList();
    private String coverUrl;
    private String publishTime;
    private Integer trackCount;
    private String description;
}
