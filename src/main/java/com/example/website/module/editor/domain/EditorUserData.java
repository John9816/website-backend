package com.example.website.module.editor.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 单个用户的学城文档数据。各功能集合以 JSON 数组字符串存储，
 * 与原 Node 版 users/{id}/*.json 一一对应。
 */
@Entity
@Table(name = "editor_user_data")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class EditorUserData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Lob
    @Column(name = "docs", columnDefinition = "LONGTEXT")
    private String docs;

    @Lob
    @Column(name = "templates", columnDefinition = "LONGTEXT")
    private String templates;

    @Lob
    @Column(name = "trash", columnDefinition = "LONGTEXT")
    private String trash;

    @Lob
    @Column(name = "notifications", columnDefinition = "LONGTEXT")
    private String notifications;

    @Lob
    @Column(name = "comments", columnDefinition = "LONGTEXT")
    private String comments;

    @Lob
    @Column(name = "mentions", columnDefinition = "LONGTEXT")
    private String mentions;

    @Lob
    @Column(name = "access_requests", columnDefinition = "LONGTEXT")
    private String accessRequests;

    @CreatedDate
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
