package com.example.website.module.reading.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_book_source", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_book_source_user_key", columnNames = {"user_id", "source_key"})
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class UserBookSource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "source_key", nullable = false, length = 80)
    private String sourceKey;

    @Column(name = "source_name", nullable = false, length = 120)
    private String sourceName;

    @Column(name = "source_url", nullable = false, length = 500)
    private String sourceUrl;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType = "book";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, length = 24)
    private String status = "unsupported";

    @Lob
    @Column(name = "definition_json", nullable = false, columnDefinition = "LONGTEXT")
    private String definitionJson;

    @Column(length = 255)
    private String message;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
