package com.example.website.module.editor.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

/** 分享 token -> 文档 owner 的全局映射。 */
@Entity
@Table(name = "editor_share")
@Getter
@Setter
@NoArgsConstructor
public class EditorShare {

    @Id
    @Column(name = "token", nullable = false, length = 96)
    private String token;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "doc_id", length = 64)
    private String docId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
