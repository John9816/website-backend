package com.example.website.dto.music;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchCollectionItem {

    private String id;
    private MusicSource source;
    private MusicSearchType type;
    private String name;
    private String artist;
    private String creatorName;
    private String coverUrl;
    private Integer trackCount;
    private Long playCount;
}
