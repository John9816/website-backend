package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import com.example.website.module.identity.api.dto.UserBriefView;
import com.example.website.module.identity.application.UserDirectoryService;
import com.example.website.module.music.api.dto.ListenRankItemView;
import com.example.website.module.music.api.dto.ListenRankingView;
import com.example.website.module.music.api.dto.MyListenTimeView;
import com.example.website.module.music.infrastructure.persistence.MusicListenStatRepository;
import com.example.website.module.music.infrastructure.persistence.MusicPlayHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Listening-time accumulation and week/month leaderboards.
 *
 * Data source: the frontend reports real played seconds via heartbeat. Until that
 * client is wired, leaderboards fall back to an estimate derived from the existing
 * play-history table, so the endpoints never return an empty board.
 */
@Service
@RequiredArgsConstructor
public class MusicListenTimeService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** Per-report clamp: a 30s heartbeat has generous headroom. */
    private static final long MAX_DELTA_SEC = 120L;
    /** Per-day clamp: a single user cannot exceed 24h of listening in one day. */
    private static final long DAY_CAP_SEC = 86_400L;
    private static final int MAX_RANK_LIMIT = 100;
    private static final int MAX_AGGREGATE_ROWS = 5000;
    private static final String SOURCE_REPORTED = "reported";
    private static final String SOURCE_ESTIMATED = "estimated";

    private final MusicListenStatRepository statRepository;
    private final MusicPlayHistoryRepository historyRepository;
    private final UserDirectoryService userDirectoryService;

    /**
     * Accept a heartbeat. Invalid/abusive values are clamped, never rejected, so a
     * client can never be hard-blocked; gaming the board only costs more requests.
     */
    @Transactional
    public void report(Long userId, Long seconds) {
        if (userId == null || seconds == null || seconds <= 0) return;
        long delta = Math.min(seconds, MAX_DELTA_SEC);
        statRepository.addSeconds(userId, java.sql.Date.valueOf(LocalDate.now(ZONE)), delta, DAY_CAP_SEC);
    }

    public ListenRankingView ranking(String period, int limit) {
        PeriodWindow w = window(period);
        int size = limit <= 0 ? 20 : Math.min(limit, MAX_RANK_LIMIT);
        Aggregate agg = load(w);

        List<Row> top = agg.rows.size() > size
                ? new ArrayList<Row>(agg.rows.subList(0, size))
                : new ArrayList<Row>(agg.rows);
        Map<Long, UserBriefView> users = loadUsers(top);

        List<ListenRankItemView> items = new ArrayList<>();
        for (Row r : top) {
            UserBriefView u = users.get(r.userId);
            if (u == null) continue;
            items.add(new ListenRankItemView(
                    items.size() + 1, r.userId, u.getUsername(), u.getAvatarUrl(), r.seconds));
        }

        ListenRankingView view = new ListenRankingView();
        view.setPeriod(w.period);
        view.setFrom(w.from.toString());
        view.setTo(w.to.toString());
        view.setSource(agg.source);
        view.setParticipants(agg.rows.size());
        view.setItems(items);
        return view;
    }

    public MyListenTimeView mine(Long userId) {
        PeriodWindow week = window("week");
        PeriodWindow month = window("month");
        Aggregate weekAgg = load(week);
        Aggregate monthAgg = load(month);

        MyListenTimeView view = new MyListenTimeView();
        view.setTotalSec(nonNull(statRepository.sumAllByUser(userId)));
        view.setWeekSec(secondsOf(weekAgg.rows, userId));
        view.setMonthSec(secondsOf(monthAgg.rows, userId));
        view.setWeekRank(rankOf(weekAgg.rows, view.getWeekSec()));
        view.setMonthRank(rankOf(monthAgg.rows, view.getMonthSec()));
        view.setWeekParticipants(weekAgg.rows.size());
        view.setMonthParticipants(monthAgg.rows.size());
        view.setWeekSource(weekAgg.source);
        view.setMonthSource(monthAgg.source);
        return view;
    }

    private Aggregate load(PeriodWindow w) {
        List<Object[]> raw = statRepository.aggregateByUser(
                w.from, w.to, PageRequest.of(0, MAX_AGGREGATE_ROWS));
        if (raw != null && !raw.isEmpty()) {
            return new Aggregate(toRows(raw), SOURCE_REPORTED);
        }
        LocalDateTime from = w.from.atStartOfDay();
        LocalDateTime to = w.to.atStartOfDay();
        List<Object[]> estimated = historyRepository.estimateDurationByUser(
                from, to, PageRequest.of(0, MAX_AGGREGATE_ROWS));
        return new Aggregate(toRows(estimated), SOURCE_ESTIMATED);
    }

    private Map<Long, UserBriefView> loadUsers(List<Row> rows) {
        if (rows.isEmpty()) return Collections.emptyMap();
        List<Long> ids = rows.stream().map(r -> r.userId).collect(Collectors.toList());
        return userDirectoryService.briefs(ids);
    }

    private static List<Row> toRows(List<Object[]> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<Row> rows = new ArrayList<>();
        for (Object[] r : raw) {
            if (r == null || r.length < 2 || r[0] == null) continue;
            long uid = ((Number) r[0]).longValue();
            long sec = r[1] == null ? 0L : ((Number) r[1]).longValue();
            if (sec > 0) rows.add(new Row(uid, sec));
        }
        return rows;
    }

    private static long secondsOf(List<Row> rows, Long userId) {
        if (userId == null) return 0L;
        for (Row r : rows) {
            if (userId.equals(r.userId)) return r.seconds;
        }
        return 0L;
    }

    /** 0 means the user has no listening time in this period. */
    private static int rankOf(List<Row> rows, long mySeconds) {
        if (mySeconds <= 0) return 0;
        int rank = 1;
        for (Row r : rows) {
            if (r.seconds > mySeconds) rank++;
        }
        return rank;
    }

    private static long nonNull(Long value) {
        return value == null ? 0L : value;
    }

    private static PeriodWindow window(String period) {
        LocalDate today = LocalDate.now(ZONE);
        if ("month".equals(period)) {
            LocalDate from = today.withDayOfMonth(1);
            return new PeriodWindow("month", from, from.plusMonths(1));
        }
        if ("week".equals(period)) {
            LocalDate from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new PeriodWindow("week", from, from.plusWeeks(1));
        }
        throw new BusinessException(400, "period must be week or month");
    }

    private static final class PeriodWindow {
        final String period;
        final LocalDate from; // inclusive
        final LocalDate to;   // exclusive

        PeriodWindow(String period, LocalDate from, LocalDate to) {
            this.period = period;
            this.from = from;
            this.to = to;
        }
    }

    private static final class Aggregate {
        final List<Row> rows;
        final String source;

        Aggregate(List<Row> rows, String source) {
            this.rows = rows;
            this.source = source;
        }
    }

    private static final class Row {
        final Long userId;
        final long seconds;

        Row(Long userId, long seconds) {
            this.userId = userId;
            this.seconds = seconds;
        }
    }
}
