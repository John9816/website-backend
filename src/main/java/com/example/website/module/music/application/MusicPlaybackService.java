package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.MusicQuality;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.PlayInfo;
import com.example.website.module.music.api.dto.SongSearchItem;
import com.example.website.config.OkHttpConfig;
import com.example.website.shared.concurrent.PerformanceSingleFlight;
import com.example.website.module.settings.application.SysConfigService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MusicPlaybackService {
    private static final long PLAY_TTL_SEC = 1200L;
    private static final long PLAY_EXPIRY_SAFETY_SEC = 30L;
    private static final long PLAY_REQUEST_BUDGET_SEC = 8L;
    private static final long DEFAULT_HEDGE_DELAY_MILLIS = 1200L;
    private static final long NETEASE_HEDGE_DELAY_MILLIS = 650L;
    public static final String CFG_PLAY_RESOLVER_ORDER = "music.play.resolverOrder";
    public static final String CFG_CROSS_SOURCE_ORDER = "music.play.crossSourceOrder";
    public static final String DEFAULT_PLAY_RESOLVER_ORDER = "primary,qq_text,cross_source";
    public static final String DEFAULT_CROSS_SOURCE_ORDER = "netease,qq,kuwo";

    private static final MusicQuality[] QUALITY_DESC = {
            MusicQuality.FLAC24,
            MusicQuality.FLAC,
            MusicQuality.K320,
            MusicQuality.K128,
    };

    private static final List<MusicSource> FALLBACK_ORDER =
            Arrays.asList(MusicSource.NETEASE, MusicSource.QQ, MusicSource.KUWO);

    private final QishuiMusicClient qishui;
    private final QishuiParseClient qishuiParse;

    private final MusicCache cache;
    private final MusicMetrics metrics;
    private final MusicLoadGuard playLoad = new MusicLoadGuard(4);
    private final SysConfigService configService;
    private final QqMusicClient qq;
    private final NeteaseMusicClient netease;
    private final KuwoMusicClient kuwo;
    private final ChkszNeteaseMusicClient chkszNetease;
    private final TuneFreePayClient tfPay;
    private final QqTextFallbackClient qqTextFallback;
    private final PlayUrlVerifier playUrlVerifier;
    private final Executor musicResolverExecutor;
    private final long hedgeDelayMillis;
    private final PerformanceSingleFlight singleFlight =
            new PerformanceSingleFlight();

    @Autowired
    public MusicPlaybackService(MusicCache cache, SysConfigService configService,
            QqMusicClient qq, NeteaseMusicClient netease, KuwoMusicClient kuwo,
            ChkszNeteaseMusicClient chkszNetease, TuneFreePayClient tfPay,
            QqTextFallbackClient qqTextFallback, PlayUrlVerifier playUrlVerifier,
            @Qualifier(OkHttpConfig.EXECUTOR_MUSIC_RESOLVER) Executor musicResolverExecutor,
            MusicMetrics metrics, QishuiMusicClient qishui, QishuiParseClient qishuiParse) {
        this(cache, configService, qq, netease, kuwo, chkszNetease, tfPay, qqTextFallback,
                playUrlVerifier, musicResolverExecutor, DEFAULT_HEDGE_DELAY_MILLIS, metrics, qishui, qishuiParse);
    }

    MusicPlaybackService(MusicCache cache, SysConfigService configService,
            QqMusicClient qq, NeteaseMusicClient netease, KuwoMusicClient kuwo,
            ChkszNeteaseMusicClient chkszNetease, TuneFreePayClient tfPay,
            QqTextFallbackClient qqTextFallback, PlayUrlVerifier playUrlVerifier,
            Executor musicResolverExecutor, long hedgeDelayMillis,
            MusicMetrics metrics, QishuiMusicClient qishui, QishuiParseClient qishuiParse) {
        this.metrics = metrics;
        this.cache = cache;
        this.configService = configService;
        this.qq = qq;
        this.netease = netease;
        this.kuwo = kuwo;
        this.chkszNetease = chkszNetease;
        this.tfPay = tfPay;
        this.qqTextFallback = qqTextFallback;
        this.playUrlVerifier = playUrlVerifier;
        this.musicResolverExecutor = musicResolverExecutor;
        this.hedgeDelayMillis = Math.max(0L, hedgeDelayMillis);
        this.qishui = qishui;
        this.qishuiParse = qishuiParse;
    }

    public PlayInfo play(String rawSource, String id, String rawQuality) {
        long startedNanos = System.nanoTime();
        MusicSource source = MusicSource.of(rawSource);
        PlaybackQualityRequest quality = PlaybackQualityRequest.parse(source, rawQuality);
        String sid = requireId(id);
        String key = "music:play:" + source.getValue() + ":" + sid + ":" + quality.getCacheValue();

        try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(PLAY_REQUEST_BUDGET_SEC, TimeUnit.SECONDS)) {
            PlayInfo cached = cache.get(key, PlayInfo.class);
            if (cached != null && acceptCachedPlayInfo(key, source, sid, cached)) {
                PlayInfo response = copyWithFromCache(cached);
                response.setRequestedQuality(quality.getRequestedValue());
                if (source == MusicSource.QISHUI) qishui.enrichCachedPlayback(response);
                logPlayResolution(source, quality, "cache", startedNanos, true, null);
                return response;
            }

            try {
                PlayInfo resolved = singleFlight.execute(key,
                        () -> {
                            PlayInfo fresh = cache.get(key, PlayInfo.class);
                            if (fresh != null && acceptCachedPlayInfo(key, source, sid, fresh)) {
                                PlayInfo response = copyWithFromCache(fresh);
                                response.setRequestedQuality(quality.getRequestedValue());
                                return response;
                            }
                            try (MusicLoadGuard.Lease lease = playLoad.acquire(source)) {
                                return resolvePlay(source, quality, sid, key);
                            }
                        },
                        MusicRequestBudget.remainingNanos(), TimeUnit.NANOSECONDS);
                // A canonical quality and its legacy alias share a flight, but each
                // caller must receive its own requested value and mutable response.
                PlayInfo response = copyPlayInfo(resolved);
                response.setRequestedQuality(quality.getRequestedValue());
                if (source == MusicSource.QISHUI) qishui.enrichCachedPlayback(response);
                boolean cacheHit = Boolean.TRUE.equals(response.getFromCache());
                logPlayResolution(source, quality, cacheHit ? "cache" : "upstream", startedNanos, cacheHit, null);
                return response;
            } catch (PerformanceSingleFlight.WaitTimeoutException ex) {
                MusicBusinessException timeout = new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT,
                        "music play resolution exceeded its time budget", ex);
                logPlayResolution(source, quality, "failed", startedNanos, false, timeout);
                throw timeout;
            } catch (RejectedExecutionException ex) {
                MusicBusinessException busy = new MusicBusinessException(MusicErrorCode.SERVICE_BUSY);
                logPlayResolution(source, quality, "failed", startedNanos, false, busy);
                throw busy;
            } catch (RuntimeException ex) {
                logPlayResolution(source, quality, "failed", startedNanos, false, ex);
                throw ex;
            }
        }
    }

    private PlayInfo resolvePlay(MusicSource source,
                                 PlaybackQualityRequest quality,
                                 String sid,
                                 String key) {
        List<PlayResolver> resolvers = applicableResolvers(source, resolvePlayResolverOrder());
        if (resolvers.isEmpty()) {
            throw new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL,
                    "no playback resolver configured for " + source.getValue());
        }

        long deadlineNanos = MusicRequestBudget.deadlineNanos();
        SongMetaLookup meta = new SongMetaLookup(source, sid);
        // Qishui's sole resolver signs a local stream ticket. Remote-source saturation
        // must not queue or reject that work; keep the same budget and resolver handling.
        Executor executor = source == MusicSource.QISHUI ? Runnable::run : musicResolverExecutor;
        ExecutorCompletionService<PlayInfo> completions = new ExecutorCompletionService<>(executor);
        Map<Future<PlayInfo>, ActiveResolver> active = new HashMap<>();
        Map<PlayResolver, MusicBusinessException> errors = new EnumMap<>(PlayResolver.class);
        int nextResolver = 0;
        long resolverHedgeDelayMillis = hedgeDelayMillisFor(source);

        submitResolver(completions, active, resolvers.get(nextResolver++), source, quality, sid, meta,
                deadlineNanos);
        long nextHedgeNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(resolverHedgeDelayMillis);

        try {
            while (!active.isEmpty() || nextResolver < resolvers.size()) {
                MusicRequestBudget.ensureRemaining();
                if (active.isEmpty()) {
                    submitResolver(completions, active, resolvers.get(nextResolver++), source, quality, sid, meta,
                            deadlineNanos);
                    nextHedgeNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(resolverHedgeDelayMillis);
                    continue;
                }

                long remainingNanos = MusicRequestBudget.remainingNanos();
                long waitNanos = remainingNanos;
                if (nextResolver < resolvers.size()) {
                    waitNanos = Math.min(waitNanos, Math.max(0L, nextHedgeNanos - System.nanoTime()));
                }

                Future<PlayInfo> completed;
                try {
                    completed = completions.poll(waitNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT,
                            "music play resolution was interrupted", ex);
                }

                if (completed == null) {
                    if (nextResolver < resolvers.size()) {
                        submitResolver(completions, active, resolvers.get(nextResolver++), source, quality, sid, meta,
                                deadlineNanos);
                        nextHedgeNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(resolverHedgeDelayMillis);
                        continue;
                    }
                    MusicRequestBudget.ensureRemaining();
                    continue;
                }

                ActiveResolver attempt = active.remove(completed);
                PlayResolver resolver = attempt.resolver;
                attempt.calls.cancel();
                try {
                    PlayInfo info = completed.get();
                    if (info == null) continue;
                    info.setRequestedQuality(quality.getRequestedValue());
                    cachePlayInfo(key, info);
                    log.debug("Music play resolver winner source={} resolver={}", source.getValue(), resolver.value);
                    return info;
                } catch (CancellationException ignored) {
                    // A cancelled loser cannot become the selected result.
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT,
                            "music play resolution was interrupted", ex);
                } catch (ExecutionException ex) {
                    MusicBusinessException error = asMusicError(ex.getCause());
                    if (resolver != null) errors.put(resolver, error);
                }
            }
        } finally {
            cancelAll(active);
        }

        for (PlayResolver resolver : resolvers) {
            MusicBusinessException error = errors.get(resolver);
            if (error != null) throw error;
        }
        throw new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL,
                "no playable url for " + source.getValue() + ":" + sid);
    }

    private long hedgeDelayMillisFor(MusicSource source) {
        if (source == MusicSource.NETEASE) {
            return Math.min(hedgeDelayMillis, NETEASE_HEDGE_DELAY_MILLIS);
        }
        return hedgeDelayMillis;
    }

    private void submitResolver(ExecutorCompletionService<PlayInfo> completions,
                                Map<Future<PlayInfo>, ActiveResolver> active,
                                PlayResolver resolver,
                                MusicSource source,
                                PlaybackQualityRequest quality,
                                String sid,
                                SongMetaLookup meta,
                                long deadlineNanos) {
        MusicRequestBudget.CallGroup calls = new MusicRequestBudget.CallGroup();
        try {
            Future<PlayInfo> future = completions.submit(() -> {
                try (MusicRequestBudget.Scope ignored = MusicRequestBudget.useDeadline(deadlineNanos, calls)) {
                    return attemptResolver(resolver, source, quality, sid, meta);
                }
            });
            active.put(future, new ActiveResolver(resolver, calls));
        } catch (RuntimeException ex) {
            calls.cancel();
            throw ex;
        }
    }

    private static final class ActiveResolver {
        final PlayResolver resolver;
        final MusicRequestBudget.CallGroup calls;
        ActiveResolver(PlayResolver resolver, MusicRequestBudget.CallGroup calls) {
            this.resolver = resolver;
            this.calls = calls;
        }
    }

    private PlayInfo attemptResolver(PlayResolver resolver,
                                     MusicSource source,
                                     PlaybackQualityRequest quality,
                                     String sid,
                                     SongMetaLookup meta) {
        long startedNanos = System.nanoTime();
        try {
            MusicRequestBudget.ensureRemaining();
            PlayInfo info;
            switch (resolver) {
                case PRIMARY:
                    info = verifyPlayableOrThrow(source, sid,
                            playWithPreferredResolver(source, sid, quality), "primary resolver");
                    identifyRecording(info, source, sid);
                    break;
                case QQ_TEXT:
                    info = verifyPlayableOrNull(source, sid,
                            tryQqTextFallback(source, sid, quality.getLegacyQuality(), meta.get()),
                            "qq text fallback");
                    break;
                case CROSS_SOURCE:
                    info = tryFallback(source, sid, quality.getLegacyQuality(), meta.get(), null);
                    break;
                default:
                    info = null;
            }
            logResolverAttempt(source, resolver, startedNanos, info == null ? "empty" : "success", null);
            return info;
        } catch (RuntimeException ex) {
            logResolverAttempt(source, resolver, startedNanos, "failed", ex);
            throw ex;
        }
    }

    private static List<PlayResolver> applicableResolvers(MusicSource source, List<PlayResolver> configured) {
        if (source == MusicSource.QISHUI) return configured.contains(PlayResolver.PRIMARY)
                ? Collections.singletonList(PlayResolver.PRIMARY) : Collections.emptyList();
        if (source == MusicSource.QQ) return configured;
        List<PlayResolver> filtered = new ArrayList<>();
        for (PlayResolver resolver : configured) {
            if (resolver != PlayResolver.QQ_TEXT) filtered.add(resolver);
        }
        return filtered;
    }

    private static void cancelAll(Map<Future<PlayInfo>, ActiveResolver> active) {
        active.forEach((future, attempt) -> {
            attempt.calls.cancel();
            future.cancel(true);
        });
        active.clear();
    }

    private static MusicBusinessException asMusicError(Throwable error) {
        if (error instanceof MusicBusinessException) return (MusicBusinessException) error;
        return new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                "music resolver failed: " + (error == null ? "unknown error" : error.getMessage()), error);
    }

    private void cachePlayInfo(String key, PlayInfo info) {
        long ttlSeconds = PLAY_TTL_SEC;
        Integer upstreamExpiry = info == null ? null : info.getExpireSec();
        if (upstreamExpiry != null && upstreamExpiry > 0) {
            long safeTtl = upstreamExpiry.longValue() - PLAY_EXPIRY_SAFETY_SEC;
            if (safeTtl <= 0L) {
                log.debug("Skipping play URL cache because upstream expiry is too close: expireSec={}", upstreamExpiry);
                return;
            }
            ttlSeconds = Math.min(ttlSeconds, safeTtl);
        }
        cache.put(key, info, ttlSeconds);
    }

    private void logResolverAttempt(MusicSource source,
                                    PlayResolver resolver,
                                    long startedNanos,
                                    String outcome,
                                    Throwable error) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        String errorType = error == null ? "none" : error.getClass().getSimpleName();
        log.info("Music resolver stage source={} resolver={} outcome={} elapsedMs={} errorType={}",
                source.getValue(), resolver.value, outcome, elapsedMillis, errorType);
    }

    private void logPlayResolution(MusicSource source,
                                   PlaybackQualityRequest quality,
                                   String outcome,
                                   long startedNanos,
                                   boolean cacheHit,
                                   Throwable error) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        String errorType = error == null ? "none" : error.getClass().getSimpleName();
        boolean busy = error instanceof MusicBusinessException
                && ((MusicBusinessException) error).getErrorCode() == MusicErrorCode.SERVICE_BUSY;
        metrics.cache(source.getValue(), "play", cacheHit);
        metrics.record(source.getValue(), "play", busy ? "rejected" : error != null ? "failure"
                : cacheHit ? "cache_hit" : "success", startedNanos);
        if (busy) metrics.rejected(source.getValue(), "play");
        log.info("Music play resolution source={} quality={} outcome={} cacheHit={} elapsedMs={} errorType={}",
                source.getValue(), quality.getCacheValue(), outcome, cacheHit, elapsedMillis, errorType);
    }

    private final class SongMetaLookup {
        private final MusicSource source;
        private final String id;
        private boolean loaded;
        private SongSearchItem value;

        private SongMetaLookup(MusicSource source, String id) {
            this.source = source;
            this.id = id;
        }

        private synchronized SongSearchItem get() {
            if (!loaded) {
                value = fetchSongInfoOn(source, id);
                loaded = true;
            }
            return value;
        }
    }

    private PlayInfo playWithPreferredResolver(MusicSource source,
                                               String id,
                                               PlaybackQualityRequest quality) {
        if (source == MusicSource.QISHUI) return qishuiParse.play(id, quality.getLegacyQuality());
        if (source == MusicSource.QQ) {
            try {
                return qq.resolvePlay(id, quality.getLegacyQuality());
            } catch (MusicBusinessException ex) {
                log.debug("Xunhuisi QQ resolver failed for {}/{} quality={}: {}",
                        source.getValue(), id, quality.getCacheValue(), ex.getMessage());
            }
        }
        if (source == MusicSource.NETEASE) {
            try {
                return chkszNetease.resolve(id, quality.getNeteaseQuality());
            } catch (MusicBusinessException ex) {
                log.debug("ChKSz NetEase resolver failed for {}/{} quality={}: {}",
                        source.getValue(), id, quality.getCacheValue(), ex.getMessage());
            }
        }
        return playWithQualityFallback(source, id, quality.getLegacyQuality());
    }

    private boolean acceptCachedPlayInfo(String key, MusicSource requestedSource, String requestedId, PlayInfo cached) {
        if (cached == null) return false;
        if (!shouldVerifyQqPlayUrl(cached)) return true;
        if (playUrlVerifier.isPlayable(cached.getPlayUrl())) return true;
        cache.invalidate(key);
        log.info("Discarding cached QQ play URL after failed probe: {}/{}",
                requestedSource.getValue(), requestedId);
        return false;
    }

    private PlayInfo playWithQualityFallback(MusicSource source, String id, MusicQuality requested) {
        MusicBusinessException lastNoUrl = null;
        for (MusicQuality q : degradeChainFrom(requested)) {
            MusicRequestBudget.ensureRemaining();
            try {
                PlayInfo info = tfPay.play(source, id, q);
                if (info == null) {
                    throw new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL,
                            "no playable url for " + source.getValue() + ":" + id);
                }
                if (isEmpty(info.getActualQuality())) {
                    info.setActualQuality(q.getValue());
                }
                if (q != requested) {
                    log.info("Quality fallback success: {}/{} {} -> {}",
                            source.getValue(), id, requested.getValue(), q.getValue());
                }
                return info;
            } catch (MusicBusinessException ex) {
                if (ex.getErrorCode() != MusicErrorCode.NO_PLAYABLE_URL) {
                    throw ex;
                }
                lastNoUrl = ex;
                log.debug("Quality fallback {}/{} quality={} -> no url, trying lower",
                        source.getValue(), id, q.getValue());
            }
        }
        throw lastNoUrl;
    }

    private static List<MusicQuality> degradeChainFrom(MusicQuality requested) {
        for (int i = 0; i < QUALITY_DESC.length; i++) {
            if (QUALITY_DESC[i] == requested) {
                return Arrays.asList(QUALITY_DESC).subList(i, QUALITY_DESC.length);
            }
        }
        return Collections.singletonList(requested);
    }

    private PlayInfo tryFallback(MusicSource requested,
                                 String requestedId,
                                 MusicQuality quality,
                                 SongSearchItem meta,
                                 MusicBusinessException originalError) {
        MusicBusinessException fallbackError = originalError == null
                ? new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL,
                "no playable url for " + requested.getValue() + ":" + requestedId)
                : originalError;

        if (fallbackError.getErrorCode() == MusicErrorCode.MISSING_UPSTREAM_TOKEN) {
            throw fallbackError;
        }

        if (meta == null || isEmpty(meta.getName())) {
            log.info("Play fallback: no song info for {}/{}, cannot cross-search.",
                    requested.getValue(), requestedId);
            throw fallbackError;
        }

        String keyword = isEmpty(meta.getArtist())
                ? meta.getName()
                : meta.getName() + " " + meta.getArtist();

        for (MusicSource other : resolveCrossSourceOrder()) {
            if (other == requested || other == MusicSource.QISHUI) continue;
            MusicRequestBudget.ensureRemaining();
            try {
                List<SongSearchItem> hits = searchOn(other, keyword, 1, 3);
                SongSearchItem pick = RecordingMatch.pick(hits, meta);
                if (pick == null) continue;

                PlayInfo alt = tfPay.play(other, pick.getId(), quality);
                if (alt == null) {
                    throw new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL,
                            "no playable url for " + other.getValue() + ":" + pick.getId());
                }
                identifyRecording(alt, other, pick.getId());
                alt.setSource(requested);
                alt.setId(requestedId);
                log.info("Play fallback success: {}/{} -> {}/{} (keyword='{}')",
                        requested.getValue(), requestedId, other.getValue(), pick.getId(), keyword);
                return alt;
            } catch (MusicBusinessException fallbackErr) {
                log.debug("Play fallback {}->{} also failed: {}",
                        requested.getValue(), other.getValue(), fallbackErr.getMessage());
            }
        }

        throw fallbackError;
    }

    private PlayInfo tryQqTextFallback(MusicSource requested,
                                       String requestedId,
                                       MusicQuality quality,
                                       SongSearchItem meta) {
        if (requested != MusicSource.QQ) return null;
        if (meta == null || isEmpty(meta.getName())) {
            log.info("QQ text fallback skipped for {} because song metadata is unavailable.", requestedId);
            return null;
        }

        try {
            PlayInfo info = qqTextFallback.fetchSongUrl(requestedId, quality, meta);
            fillMissingMetadata(info, meta);
            if (info != null) {
                info.setActualId(null);
                info.setLyricMatch("unverified");
            }
            logQqTextFallbackMatch(meta, info, requestedId);
            log.info("QQ text fallback success: qq/{} -> keyword='{}'",
                    requestedId, buildFallbackKeyword(meta));
            return info;
        } catch (MusicBusinessException ex) {
            log.debug("QQ text fallback failed for {}: {}", requestedId, ex.getMessage());
            return null;
        }
    }

    private PlayInfo verifyPlayableOrThrow(MusicSource requestedSource,
                                           String requestedId,
                                           PlayInfo info,
                                           String stage) {
        if (info == null) {
            throw new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL,
                    "no playable url for " + requestedSource.getValue() + ":" + requestedId);
        }
        if (!shouldVerifyQqPlayUrl(info)) return info;
        if (playUrlVerifier.isPlayable(info.getPlayUrl())) return info;
        log.info("QQ play URL probe failed for stage={} song={}/{}",
                stage, requestedSource.getValue(), requestedId);
        throw new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL,
                "validated unplayable url for " + requestedSource.getValue() + ":" + requestedId);
    }

    private PlayInfo verifyPlayableOrNull(MusicSource requestedSource,
                                          String requestedId,
                                          PlayInfo info,
                                          String stage) {
        try {
            return verifyPlayableOrThrow(requestedSource, requestedId, info, stage);
        } catch (MusicBusinessException ex) {
            if (ex.getErrorCode() != MusicErrorCode.NO_PLAYABLE_URL) {
                throw ex;
            }
            log.info("QQ play URL probe forced fallback for stage={} song={}/{}",
                    stage, requestedSource.getValue(), requestedId);
            return null;
        }
    }

    private SongSearchItem fetchSongInfoOn(MusicSource source, String id) {
        switch (source) {
            case QISHUI:
                return qishui.fetchSongInfo(id);
            case QQ:
                return qq.fetchSongInfo(id);
            case NETEASE:
                return netease.fetchSongInfo(id);
            case KUWO:
                return kuwo.fetchSongInfo(id);
            default:
                return null;
        }
    }

    private List<SongSearchItem> searchOn(MusicSource source, String keyword, int page, int pageSize) {
        switch (source) {
            case QISHUI:
                return qishui.search(keyword, page, pageSize);
            case QQ:
                return qq.search(keyword, page, pageSize);
            case NETEASE:
                return netease.search(keyword, page, pageSize);
            case KUWO:
                return kuwo.search(keyword, page, pageSize);
            default:
                return Collections.emptyList();
        }
    }

    private static void identifyRecording(PlayInfo info, MusicSource source, String id) {
        if (info.getActualSource() == null) info.setActualSource(source);
        if (info.getActualSource() == source) {
            info.setActualId(id);
            info.setLyricMatch("exact");
        } else {
            // A provider may itself switch sources without returning its selected ID.
            info.setActualId(null);
            info.setLyricMatch("unverified");
        }
    }

    private static String normalise(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("\\s*[\\(\\[].*?[\\)\\]]", "").trim();
    }

    private boolean shouldVerifyQqPlayUrl(PlayInfo info) {
        if (info == null || info.getActualSource() != MusicSource.QQ || isEmpty(info.getPlayUrl())) {
            return false;
        }
        HttpUrl url = HttpUrl.parse(info.getPlayUrl());
        if (url == null) return false;
        String host = url.host().toLowerCase();
        return host.equals("wx.music.tc.qq.com")
                || host.endsWith(".tc.qq.com")
                || host.endsWith(".qqmusic.qq.com");
    }

    private SongSearchItem ensureMeta(MusicSource source, String id, SongSearchItem current) {
        return current != null ? current : fetchSongInfoOn(source, id);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static void fillMissingMetadata(PlayInfo info, SongSearchItem meta) {
        if (info == null || meta == null) return;
        if (isEmpty(info.getName())) info.setName(meta.getName());
        if (isEmpty(info.getArtist())) info.setArtist(meta.getArtist());
        if (isEmpty(info.getAlbum())) info.setAlbum(meta.getAlbum());
        if (isEmpty(info.getCoverUrl())) info.setCoverUrl(meta.getCoverUrl());
        if (info.getDurationSec() == null) info.setDurationSec(meta.getDurationSec());
    }

    private void logQqTextFallbackMatch(SongSearchItem requestedMeta, PlayInfo resolvedInfo, String requestedId) {
        if (requestedMeta == null || resolvedInfo == null) return;
        String requestedName = normalise(requestedMeta.getName());
        String resolvedName = normalise(resolvedInfo.getName());
        String requestedArtist = normaliseArtist(requestedMeta.getArtist());
        String resolvedArtist = normaliseArtist(resolvedInfo.getArtist());
        if (!requestedName.equals(resolvedName)
                || (!requestedArtist.isEmpty() && !resolvedArtist.isEmpty() && !requestedArtist.equals(resolvedArtist))) {
            log.warn("QQ text fallback potential mismatch for {}: requested='{} - {}', resolved='{} - {}'",
                    requestedId,
                    requestedMeta.getName(),
                    requestedMeta.getArtist(),
                    resolvedInfo.getName(),
                    resolvedInfo.getArtist());
        }
    }

    private static String normaliseArtist(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replace("\uFF0F", "/")
                .replace("、", "/")
                .replace("&", "/")
                .replaceAll("\\s*/\\s*", "/")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String buildFallbackKeyword(SongSearchItem meta) {
        if (meta == null || isEmpty(meta.getName())) return "";
        if (isEmpty(meta.getArtist())) return meta.getName();
        return meta.getName() + " " + meta.getArtist();
    }

    private List<PlayResolver> resolvePlayResolverOrder() {
        String configured = configuredValue(CFG_PLAY_RESOLVER_ORDER, DEFAULT_PLAY_RESOLVER_ORDER);
        List<PlayResolver> parsed = new ArrayList<>();
        for (String token : configured.split("[,\\r\\n]+")) {
            PlayResolver resolver = PlayResolver.of(token);
            if (resolver != null && !parsed.contains(resolver)) {
                parsed.add(resolver);
            }
        }
        if (parsed.isEmpty()) {
            log.warn("Invalid {}, falling back to default: {}", CFG_PLAY_RESOLVER_ORDER, DEFAULT_PLAY_RESOLVER_ORDER);
            return PlayResolver.DEFAULT_ORDER;
        }
        return parsed;
    }

    private List<MusicSource> resolveCrossSourceOrder() {
        String configured = configuredValue(CFG_CROSS_SOURCE_ORDER, DEFAULT_CROSS_SOURCE_ORDER);
        List<MusicSource> parsed = new ArrayList<>();
        for (String token : configured.split("[,\\r\\n]+")) {
            try {
                MusicSource source = MusicSource.of(token.trim());
                if (!parsed.contains(source)) {
                    parsed.add(source);
                }
            } catch (MusicBusinessException ignored) {
                // Ignore unknown admin-configured sources and fall back below if nothing valid remains.
            }
        }
        if (parsed.isEmpty()) {
            log.warn("Invalid {}, falling back to default: {}", CFG_CROSS_SOURCE_ORDER, DEFAULT_CROSS_SOURCE_ORDER);
            return FALLBACK_ORDER;
        }
        return parsed;
    }

    private String configuredValue(String key, String fallback) {
        try {
            return configService.getValue(key)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .orElse(fallback);
        } catch (RuntimeException ex) {
            log.warn("Failed to read {}, using default: {}", key, fallback);
            return fallback;
        }
    }

    private PlayInfo copyWithFromCache(PlayInfo src) {
        PlayInfo response = copyPlayInfo(src);
        response.setFromCache(Boolean.TRUE);
        return response;
    }

    private PlayInfo copyPlayInfo(PlayInfo src) {
        PlayInfo p = new PlayInfo();
        p.setId(src.getId());
        p.setSource(src.getSource());
        p.setActualSource(src.getActualSource());
        p.setActualId(src.getActualId());
        p.setLyricMatch(src.getLyricMatch());
        p.setName(src.getName());
        p.setArtist(src.getArtist());
        p.setAlbum(src.getAlbum());
        p.setCoverUrl(src.getCoverUrl());
        p.setDurationSec(src.getDurationSec());
        p.setPlayUrl(src.getPlayUrl());
        p.setRequestedQuality(src.getRequestedQuality());
        p.setActualQuality(src.getActualQuality());
        p.setFileSize(src.getFileSize());
        p.setExpireSec(src.getExpireSec());
        p.setFromCache(src.getFromCache());
        p.setDirectUrl(src.getDirectUrl());
        p.setPlayAuth(src.getPlayAuth());
        p.setLyric(src.getLyric());
        return p;
    }

    private static String requireId(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new MusicBusinessException(MusicErrorCode.SONG_NOT_FOUND, "id is required");
        }
        return id.trim();
    }

    private enum PlayResolver {
        PRIMARY("primary"),
        QQ_TEXT("qq_text"),
        CROSS_SOURCE("cross_source");

        static final List<PlayResolver> DEFAULT_ORDER =
                Collections.unmodifiableList(Arrays.asList(PRIMARY, QQ_TEXT, CROSS_SOURCE));

        private final String value;

        PlayResolver(String value) {
            this.value = value;
        }

        static PlayResolver of(String raw) {
            if (raw == null) return null;
            String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            for (PlayResolver resolver : values()) {
                if (resolver.value.equals(normalized)) {
                    return resolver;
                }
            }
            return null;
        }
    }
}
