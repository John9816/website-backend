package com.example.website.repository;

import com.example.website.entity.MusicPlayHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MusicPlayHistoryRepository extends JpaRepository<MusicPlayHistory, Long> {

    Page<MusicPlayHistory> findByUserIdOrderByPlayedAtDescIdDesc(Long userId, Pageable pageable);

    Optional<MusicPlayHistory> findByIdAndUserId(Long id, Long userId);

    Optional<MusicPlayHistory> findByUserIdAndSourceAndSongId(Long userId, String source, String songId);

    Optional<MusicPlayHistory> findFirstByUserIdOrderByPlayedAtAscIdAsc(Long userId);

    long countByUserId(Long userId);

    @Query("select h.id from MusicPlayHistory h where h.userId = :userId " +
            "order by h.playedAt asc, h.id asc")
    List<Long> findOldestIds(@Param("userId") Long userId, Pageable pageable);

    @Modifying
    @Query("delete from MusicPlayHistory h where h.id in :ids")
    int deleteByIdIn(@Param("ids") List<Long> ids);
}
