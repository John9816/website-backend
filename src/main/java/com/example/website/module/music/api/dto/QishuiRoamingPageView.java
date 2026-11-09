package com.example.website.module.music.api.dto;

import lombok.*;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QishuiRoamingPageView {
    private String sessionId;
    private MusicSource source;
    private String modeId;
    private long sequence;
    private long nextSequence;
    private boolean hasMore;
    private Instant expiresAt;
    private List<SongSearchItem> list;
}
