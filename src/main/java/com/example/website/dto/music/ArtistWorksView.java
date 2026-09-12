package com.example.website.dto.music;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArtistWorksView {

    private String artistId;
    private MusicSource source;
    private String type;
    private int page;
    private int pageSize;
    private Integer total;
    private List<SongSearchItem> songs = Collections.emptyList();
    private List<AlbumSummaryItem> albums = Collections.emptyList();
}
