package com.example.website.dto.music;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaylistItem {

    private String id;
    private MusicSource source;
    private String name;
    private String coverUrl;
    private String description;
    private String creatorName;
    private Integer trackCount;
    private Long playCount;
}
