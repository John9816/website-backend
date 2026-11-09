package com.example.website.module.music.api.dto;

import lombok.*;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QishuiDiscoveryView {
    private MusicSource source;
    private List<SongSearchItem> list;
}
