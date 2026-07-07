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
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "content_article", indexes = {
        @Index(name = "idx_content_article_user_updated", columnList = "user_id,updated_at,id"),
        @Index(name = "idx_content_article_user_category_updated", columnList = "user_id,category,updated_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class ContentArticle {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_WECHAT_DRAFT = "WECHAT_DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 500)
    private String digest;

    @Lob
    @Column(name = "content_markdown", columnDefinition = "LONGTEXT")
    private String contentMarkdown;

    @Lob
    @Column(name = "content_html", nullable = false, columnDefinition = "LONGTEXT")
    private String contentHtml;

    @Column(name = "cover_prompt", length = 2000)
    private String coverPrompt;

    @Column(name = "cover_image_url", length = 1000)
    private String coverImageUrl;

    @Lob
    @Column(name = "topics_json", columnDefinition = "LONGTEXT")
    private String topicsJson;

    @Column(name = "tags_json", length = 2000)
    private String tagsJson;

    @Column(name = "risk_tips_json", length = 2000)
    private String riskTipsJson;

    @Column(length = 100)
    private String model;

    @Column(length = 80)
    private String category;

    @Column(name = "layout_theme", length = 40)
    private String layoutTheme;

    @Column(name = "image_mode", length = 40)
    private String imageMode;

    @Lob
    @Column(name = "automation_json", columnDefinition = "LONGTEXT")
    private String automationJson;

    @Column(nullable = false, length = 40)
    private String status = STATUS_DRAFT;

    @Column(name = "wechat_media_id", length = 200)
    private String wechatMediaId;

    @Column(name = "wechat_publish_id", length = 200)
    private String wechatPublishId;

    @Column(name = "wechat_url", length = 1000)
    private String wechatUrl;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
