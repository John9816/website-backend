package com.example.website.repository;

import com.example.website.entity.MusicShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MusicShareRepository extends JpaRepository<MusicShare, Long> {

    Optional<MusicShare> findByUserIdAndSourceAndSongId(Long userId, String source, String songId);

    Optional<MusicShare> findByToken(String token);

    boolean existsByToken(String token);

    @Modifying
    @Query("update MusicShare s set s.viewCount = s.viewCount + 1 where s.id = :id")
    int incrementViewCount(@Param("id") Long id);
}
