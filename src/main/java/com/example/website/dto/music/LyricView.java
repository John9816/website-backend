package com.example.website.dto.music;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standalone response for GET /api/v1/music/lyric — keeps null fields for
 * the same reason as LyricInfo (spec §9.4 wants explicit null karaoke).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class LyricView {

    private String id;
    private MusicSource source;
    private String lineLyrics;
    private String karaokeLyrics;
}
