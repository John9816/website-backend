package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.dto.music.LyricInfo;
import com.example.website.dto.music.LyricView;
import com.example.website.dto.music.MusicQuality;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.PlayInfo;
import com.example.website.dto.music.SongSearchItem;
import com.example.website.service.PerformanceSingleFlight;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/** Official-first lyric loading with a shared deadline, coalescing and recoverable backoff. */
@Service
public class MusicLyricService {
    @org.springframework.beans.factory.annotation.Autowired
    private QishuiMusicClient qishui;

    private static final long WORD_TTL_SECONDS = 6L * 60L * 60L;
    private static final long ORDINARY_TTL_SECONDS = 10L * 60L;
    private static final long OFFICIAL_FAILURE_TTL_SECONDS = 30L;
    private static final long TOTAL_FAILURE_TTL_SECONDS = 5L;
    private static final long REQUEST_BUDGET_SECONDS = 8L;

    private final MusicCache cache;
    private final QqMusicClient qq;
    private final NeteaseMusicClient netease;
    private final KuwoMusicClient kuwo;
    private final TuneFreePayClient tfPay;
    private final QqTextFallbackClient qqTextFallback;
    private final MusicMetrics metrics;
    private final PerformanceSingleFlight singleFlight = new PerformanceSingleFlight();

    public MusicLyricService(MusicCache cache,
                             QqMusicClient qq,
                             NeteaseMusicClient netease,
                             KuwoMusicClient kuwo,
                             TuneFreePayClient tfPay,
                             QqTextFallbackClient qqTextFallback,
                             MusicMetrics metrics) {
        this.cache = cache;
        this.qq = qq;
        this.netease = netease;
        this.kuwo = kuwo;
        this.tfPay = tfPay;
        this.qqTextFallback = qqTextFallback;
        this.metrics = metrics;
    }

    public LyricView lyric(String rawSource, String id) {
        long startedNanos = System.nanoTime();
        String metricSource = "unknown";
        String outcome = "failure";
        try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(REQUEST_BUDGET_SECONDS, TimeUnit.SECONDS)) {
            MusicSource source = MusicSource.of(rawSource);
            metricSource = source.getValue();
            String sid = requireId(id);
            LyricView cached = cachedAvailable(source, sid);
            metrics.cache(metricSource, "lyric", cached != null);
            if (cached != null) {
                outcome = Boolean.TRUE.equals(cached.getOfficial()) ? "cache_hit" : "cooldown";
                return measured(cached);
            }
            LyricView result = singleFlight.execute(key("flight", source, sid), () -> load(source, sid),
                    MusicRequestBudget.remainingNanos(), TimeUnit.NANOSECONDS);
            outcome = Boolean.TRUE.equals(result.getOfficial()) ? "success" : "fallback";
            return measured(result);
        } catch (PerformanceSingleFlight.WaitTimeoutException ex) {
            outcome = "timeout";
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT, "lyric request exceeded its time budget");
        } catch (MusicBusinessException ex) {
            if (ex.getErrorCode() == MusicErrorCode.UPSTREAM_TIMEOUT) outcome = "timeout";
            throw ex;
        } finally {
            metrics.record(metricSource, "lyric", outcome, startedNanos);
        }
    }

    private LyricView measured(LyricView lyric) {
        metrics.lyric(lyric.getSource().getValue(), lyric.getProvider(),
                isEmpty(lyric.getKaraokeLyrics()) ? "line" : "word");
        return lyric;
    }

    private LyricView cachedAvailable(MusicSource source, String id) {
        LyricView official = cache.get(key("official", source, id), LyricView.class);
        if (official != null) return official;
        RecentFailure cooldown = cache.get(key("cooldown", source, id), RecentFailure.class);
        if (cooldown == null) return null;
        LyricView fallback = cache.get(key("fallback", source, id), LyricView.class);
        if (fallback != null) return fallback;
        RecentFailure failure = cache.get(key("failure", source, id), RecentFailure.class);
        if (failure != null) throw failure.error;
        return null;
    }

    private LyricView load(MusicSource source, String id) {
        // A preceding flight can finish after this caller's initial cache lookup.
        LyricView cached = cachedAvailable(source, id);
        if (cached != null) return cached;
        RecentFailure recentOfficial = cache.get(key("cooldown", source, id), RecentFailure.class);
        MusicBusinessException officialError = recentOfficial == null ? null : recentOfficial.error;
        if (officialError == null) {
            try {
                MusicRequestBudget.ensureRemaining();
                LyricView official = fetchOfficial(source, id);
                MusicRequestBudget.ensureRemaining();
                cache.put(key("official", source, id), official,
                        isEmpty(official.getKaraokeLyrics()) ? ORDINARY_TTL_SECONDS : WORD_TTL_SECONDS);
                cache.invalidate(key("cooldown", source, id));
                cache.invalidate(key("failure", source, id));
                return official;
            } catch (MusicBusinessException ex) {
                officialError = ex;
                cache.put(key("cooldown", source, id), new RecentFailure(ex), OFFICIAL_FAILURE_TTL_SECONDS);
            }
        }

        LyricView fallback = cache.get(key("fallback", source, id), LyricView.class);
        if (fallback != null) return fallback;
        MusicBusinessException failure = officialError;
        try {
            MusicRequestBudget.ensureRemaining();
            fallback = fetchFallback(source, id);
            MusicRequestBudget.ensureRemaining();
            if (fallback != null) {
                cache.put(key("fallback", source, id), fallback, ORDINARY_TTL_SECONDS);
                cache.invalidate(key("failure", source, id));
                return fallback;
            }
        } catch (MusicBusinessException ex) {
            if (ex.getErrorCode() == MusicErrorCode.UPSTREAM_TIMEOUT) failure = ex;
        }
        cache.put(key("failure", source, id), new RecentFailure(failure), TOTAL_FAILURE_TTL_SECONDS);
        throw failure;
    }

    private LyricView fetchOfficial(MusicSource source, String id) {
        LyricInfo lyric;
        switch (source) {
            case QISHUI:
                lyric = qishui.fetchLyrics(id);
                break;
            case QQ:
                lyric = qq.fetchLyrics(id);
                break;
            case NETEASE:
                String[] both = netease.fetchLyric(id);
                lyric = both == null ? null : new LyricInfo(both.length > 0 ? both[0] : null,
                        both.length > 1 ? both[1] : null);
                break;
            case KUWO:
                lyric = kuwo.fetchLyrics(id);
                break;
            default:
                throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
        }
        if (empty(lyric)) throw new MusicBusinessException(MusicErrorCode.UPSTREAM_LYRIC_FAILED, "official lyric unavailable");
        return new LyricView(id, source, lyric.getLineLyrics(), lyric.getKaraokeLyrics(), source.getValue(), Boolean.TRUE);
    }

    private LyricView fetchFallback(MusicSource source, String id) {
        LyricInfo lyric;
        String provider;
        switch (source) {
            case QISHUI:
                return null;
            case QQ:
                MusicRequestBudget.ensureRemaining();
                SongSearchItem meta = qq.fetchSongInfo(id);
                if (meta == null || isEmpty(meta.getName())) return null;
                MusicRequestBudget.ensureRemaining();
                lyric = qqTextFallback.fetchLyric(meta);
                provider = "cyapi";
                break;
            case KUWO:
                MusicRequestBudget.ensureRemaining();
                PlayInfo play = tfPay.play(source, id, MusicQuality.FLAC);
                lyric = play == null ? null : play.getLyric();
                provider = "tunefree";
                break;
            default:
                return null;
        }
        if (empty(lyric)) return null;
        return new LyricView(id, source, lyric.getLineLyrics(), lyric.getKaraokeLyrics(), provider, Boolean.FALSE);
    }

    private static String key(String kind, MusicSource source, String id) {
        return "music:lyric:" + kind + ":v4:" + source.getValue() + ":" + id;
    }

    private static String requireId(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new MusicBusinessException(MusicErrorCode.SONG_NOT_FOUND, "id is required");
        }
        return id.trim();
    }

    private static boolean empty(LyricInfo lyric) {
        return lyric == null || (isEmpty(lyric.getLineLyrics()) && isEmpty(lyric.getKaraokeLyrics()));
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static final class RecentFailure {
        private final MusicBusinessException error;

        private RecentFailure(MusicBusinessException error) {
            this.error = error;
        }
    }
}
