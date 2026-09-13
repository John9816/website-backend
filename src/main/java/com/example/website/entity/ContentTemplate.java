package com.example.website.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "content_template", indexes = {
        @Index(name = "idx_user_category", columnList = "user_id,category"),
        @Index(name = "idx_type_active", columnList = "template_type,is_active"),
        @Index(name = "idx_sort", columnList = "sort_order,id")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class ContentTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(length = 80)
    private String category;

    @Column(name = "template_type", nullable = false, length = 40)
    private String templateType = "CUSTOM";

    @Lob
    @Column(name = "content_template", nullable = false, columnDefinition = "LONGTEXT")
    private String contentTemplate;

    @Lob
    @Column(name = "prompt_template", columnDefinition = "TEXT")
    private String promptTemplate;

    @Lob
    @Column(name = "default_angle", columnDefinition = "TEXT")
    private String defaultAngle;

    @Column(name = "default_audience", length = 500)
    private String defaultAudience;

    @Column(name = "default_tone", length = 500)
    private String defaultTone;

    @Column(name = "cover_style", length = 500)
    private String coverStyle;

    @Column(name = "layout_theme", length = 40)
    private String layoutTheme = "clean";

    @Column(name = "image_mode", length = 40)
    private String imageMode = "generate";

    @Column(name = "tags_json", length = 1000)
    private String tagsJson;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "usage_count")
    private Integer usageCount = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
