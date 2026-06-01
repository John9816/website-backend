package com.example.website.repository;

import com.example.website.entity.UserPlaylistItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserPlaylistItemRepository extends JpaRepository<UserPlaylistItem, Long> {

    Page<UserPlaylistItem> findByPlaylistIdOrderBySortOrderAscIdAsc(Long playlistId, Pageable pageable);

    Optional<UserPlaylistItem> findByIdAndPlaylistId(Long id, Long playlistId);

    long countByPlaylistId(Long playlistId);

    void deleteByPlaylistId(Long playlistId);
}
