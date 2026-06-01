package com.example.website.dto.music;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToplistDetailView {

    private String id;
    private MusicSource source;
    private String name;
    private String coverUrl;
    private String description;
    private String updateTime;
    private int page;
    private int pageSize;
    /** Total songs on the server side; may be null if the upstream didn't report it. */
    private Integer total;
    private List<SongSearchItem> list;
}
