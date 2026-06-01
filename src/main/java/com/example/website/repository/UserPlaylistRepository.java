package com.example.website.repository;

import com.example.website.entity.UserPlaylist;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserPlaylistRepository extends JpaRepository<UserPlaylist, Long> {

    Page<UserPlaylist> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    Optional<UserPlaylist> findByIdAndUserId(Long id, Long userId);

    Optional<UserPlaylist> findByUserIdAndSourceAndSourceId(Long userId, String source, String sourceId);
}
