package com.example.website.dto.music;

import com.example.website.entity.UserPlaylist;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPlaylistView {

    private Long id;
    private String name;
    private String coverUrl;
    private String description;
    private String source;
    private String sourceId;
    private String sourceUrl;
    private String creatorName;
    private Integer trackCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static UserPlaylistView from(UserPlaylist e) {
        return new UserPlaylistView(
                e.getId(),
                e.getName(),
                e.getCoverUrl(),
                e.getDescription(),
                e.getSource(),
                e.getSourceId(),
                e.getSourceUrl(),
                e.getCreatorName(),
                e.getTrackCount(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
