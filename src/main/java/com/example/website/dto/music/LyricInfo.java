package com.example.website.dto.music;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lyric block embedded in PlayInfo. `karaokeLyrics` is kept as explicit null
 * for QQ (没有逐字) per spec §9.4, so we override the global non_null setting.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class LyricInfo {

    private String lineLyrics;
    private String karaokeLyrics;
}
