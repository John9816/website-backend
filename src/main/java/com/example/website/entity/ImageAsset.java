package com.example.website.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "image_asset", indexes = @Index(name = "idx_image_asset_url_hash", columnList = "url_hash"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class ImageAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "image_url", nullable = false, length = 1000)
    private String imageUrl;

    @Column(name = "url_hash", nullable = false, length = 64)
    private String urlHash;

    @Column(length = 100)
    private String host;

    @Column(name = "delete_url", length = 2048)
    private String deleteUrl;

    @Column(length = 255)
    private String source;

    @Lob
    @Column(name = "response_json", nullable = false, columnDefinition = "LONGTEXT")
    private String responseJson;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "byte_size")
    private Long byteSize;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
