package com.example.website.module.music.infrastructure.persistence;

import com.example.website.module.music.domain.MusicPlayHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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

    /**
     * Cold-start fallback for listening-time leaderboards: the history table keeps one
     * row per (user, song), so this estimates "length of distinct songs played" rather
     * than real listened seconds. Only used when no heartbeat data exists for the period.
     */
    @Query("select h.userId, coalesce(sum(h.durationSec), 0) from MusicPlayHistory h "
            + "where h.playedAt >= :from and h.playedAt < :to "
            + "group by h.userId order by sum(h.durationSec) desc, h.userId asc")
    List<Object[]> estimateDurationByUser(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to,
                                          Pageable pageable);

    @Query("select coalesce(sum(h.durationSec), 0) from MusicPlayHistory h "
            + "where h.userId = :userId and h.playedAt >= :from and h.playedAt < :to")
    Long estimateDurationForUser(@Param("userId") Long userId,
                                 @Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to);
}
