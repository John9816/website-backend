package com.example.website.dto.music;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToplistItem {

    private String id;
    private MusicSource source;
    private String name;
    private String coverUrl;
    private String description;
    /** Human-readable update hint (e.g. "每周一更新") or upstream epoch-millis — pass-through only. */
    private String updateTime;
}
