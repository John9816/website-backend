package com.example.website.module.assets.infrastructure.persistence;

import com.example.website.module.assets.domain.ImageAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ImageAssetRepository extends JpaRepository<ImageAsset, Long> {
    Optional<ImageAsset> findFirstByUrlHash(String urlHash);
}
