package com.example.website.module.editor.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;

/** 用户在线心跳。读取时按 at 时间戳过滤 TTL（60 秒）。 */
@Entity
@Table(name = "editor_presence")
@Getter
@Setter
@NoArgsConstructor
public class EditorPresence {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "name", length = 128)
    private String name;

    @Column(name = "doc_id", length = 64)
    private String docId;

    @Column(name = "at", nullable = false)
    private Long at;
}
