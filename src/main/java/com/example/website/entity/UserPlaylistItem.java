package com.example.website.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EntityListeners;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_playlist_item", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_playlist_item_song", columnNames = {"playlist_id", "source", "song_id"})
}, indexes = {
        @Index(name = "idx_user_playlist_item_order", columnList = "playlist_id,sort_order,id")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class UserPlaylistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "playlist_id", nullable = false)
    private Long playlistId;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "song_id", nullable = false, length = 100)
    private String songId;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(length = 300)
    private String artist;

    @Column(length = 300)
    private String album;

    @Column(name = "cover_url", length = 1000)
    private String coverUrl;

    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
