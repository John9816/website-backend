package com.example.website.repository;

import com.example.website.entity.MusicFavorite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MusicFavoriteRepository extends JpaRepository<MusicFavorite, Long> {

    Page<MusicFavorite> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    Optional<MusicFavorite> findByUserIdAndSourceAndSongId(Long userId, String source, String songId);

    boolean existsByUserIdAndSourceAndSongId(Long userId, String source, String songId);

    List<MusicFavorite> findByUserIdAndSourceAndSongIdIn(Long userId, String source, List<String> songIds);
}
