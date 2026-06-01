package com.example.website.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
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
@Table(name = "user_playlist", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_playlist_user_source", columnNames = {"user_id", "source", "source_id"})
}, indexes = {
        @Index(name = "idx_user_playlist_user_created", columnList = "user_id,created_at,id")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class UserPlaylist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(name = "cover_url", length = 1000)
    private String coverUrl;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "source_id", nullable = false, length = 100)
    private String sourceId;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @Column(name = "creator_name", length = 300)
    private String creatorName;

    @Column(name = "track_count", nullable = false)
    private Integer trackCount = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
