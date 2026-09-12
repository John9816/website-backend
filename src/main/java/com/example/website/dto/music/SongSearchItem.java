package com.example.website.dto.music;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SongSearchItem {

    private String id;
    private MusicSource source;
    private String name;
    private String artist;
    /** Primary artist id, kept for clients that only display one artist. */
    private String artistId;
    /** IDs in the same order as the names joined into {@link #artist}. */
    private List<String> artistIds = Collections.emptyList();
    private String album;
    private String albumId;
    private String coverUrl;
    private Long durationMs;
    private Integer durationSec;
    private List<String> availableQualities;
}
