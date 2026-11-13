package com.example.website.module.music.infrastructure.persistence;

import com.example.website.module.music.domain.MusicListenStat;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface MusicListenStatRepository extends JpaRepository<MusicListenStat, Long> {

    /**
     * Atomic per-day accumulation. LEAST() clamps the daily total instead of rejecting
     * the request, so abusive clients can only ever reach the cap (raising cost, not
     * blocking legitimate playback). Timestamps are written explicitly because
     * ddl-auto=update creates the table without the defaults from the migration SQL.
     */
    @Modifying
    @Query(value = "INSERT INTO music_listen_stat (user_id, stat_date, total_sec, created_at, updated_at) "
            + "VALUES (:userId, :statDate, :deltaSec, NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE total_sec = LEAST(total_sec + :deltaSec, :dayCapSec), "
            + "updated_at = NOW()",
            nativeQuery = true)
    int addSeconds(@Param("userId") Long userId,
                   @Param("statDate") java.sql.Date statDate,
                   @Param("deltaSec") long deltaSec,
                   @Param("dayCapSec") long dayCapSec);

    /** (userId, seconds) ordered by seconds desc; used for both leaderboards and rank lookup. */
    @Query("select s.userId, sum(s.totalSec) from MusicListenStat s "
            + "where s.statDate >= :from and s.statDate < :to "
            + "group by s.userId order by sum(s.totalSec) desc, s.userId asc")
    List<Object[]> aggregateByUser(@Param("from") LocalDate from,
                                   @Param("to") LocalDate to,
                                   Pageable pageable);

    @Query("select coalesce(sum(s.totalSec), 0) from MusicListenStat s "
            + "where s.userId = :userId and s.statDate >= :from and s.statDate < :to")
    Long sumByUser(@Param("userId") Long userId,
                   @Param("from") LocalDate from,
                   @Param("to") LocalDate to);

    @Query("select coalesce(sum(s.totalSec), 0) from MusicListenStat s where s.userId = :userId")
    Long sumAllByUser(@Param("userId") Long userId);
}
