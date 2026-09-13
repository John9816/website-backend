package com.example.website.entity;

import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "kb_document")
public class KbDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "content", columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "content_html", columnDefinition = "LONGTEXT")
    private String contentHtml;

    @Column(name = "slug", length = 200)
    private String slug;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "status", nullable = false, length = 40)
    private String status = "DRAFT";

    @Column(name = "visibility", nullable = false, length = 40)
    private String visibility = "PRIVATE";

    @Column(name = "share_token", length = 64)
    private String shareToken;

    @Column(name = "cover_image_url", length = 1000)
    private String coverImageUrl;

    @Column(name = "tags_json", length = 1000)
    private String tagsJson;

    @Column(name = "view_count")
    private Integer viewCount = 0;

    @Column(name = "word_count")
    private Integer wordCount = 0;

    @Column(name = "reading_time_minutes")
    private Integer readingTimeMinutes = 0;

    @Column(name = "last_edited_by")
    private Long lastEditedBy;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
