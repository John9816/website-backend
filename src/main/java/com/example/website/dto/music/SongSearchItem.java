package com.example.website.dto.music;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SongSearchItem {

    private String id;
    private MusicSource source;
    private String name;
    private String artist;
    private String album;
    private String albumId;
    private String coverUrl;
    private Long durationMs;
    private Integer durationSec;
    private List<String> availableQualities;
}
