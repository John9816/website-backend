package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.dto.music.ArtistDetailView;
import com.example.website.dto.music.ArtistWorksView;
import com.example.website.dto.music.AlbumDetailView;
import com.example.website.dto.music.LyricInfo;
import com.example.website.dto.music.LyricView;
import com.example.website.dto.music.MusicQuality;
import com.example.website.dto.music.MusicSearchType;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.PlayInfo;
import com.example.website.dto.music.PlaylistDetailView;
import com.example.website.dto.music.PlaylistListView;
import com.example.website.dto.music.SearchCollectionItem;
import com.example.website.dto.music.SearchResultView;
import com.example.website.dto.music.SongSearchItem;
import com.example.website.dto.music.ToplistDetailView;
import com.example.website.dto.music.ToplistItem;
import com.example.website.dto.music.ToplistListView;
import com.example.website.config.OkHttpConfig;
import com.example.website.service.PerformanceSingleFlight;
import com.example.website.service.SysConfigService;
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
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MusicService {

    private static final long SEARCH_TTL_SEC = 60L;
    private static final long PLAY_TTL_SEC = 1200L;
    private static final long PLAY_EXPIRY_SAFETY_SEC = 30L;
    private static final long PLAY_REQUEST_BUDGET_SEC = 8L;
    private static final long DEFAULT_HEDGE_DELAY_MILLIS = 1200L;
    private static final long NETEASE_HEDGE_DELAY_MILLIS = 650L;
    private static final long LYRIC_TTL_SEC = 6L * 60L * 60L;
    private static final long LYRIC_FALLBACK_TTL_SEC = 10L * 60L;
    private static final long TOPLIST_LIST_TTL_SEC = 30L * 60L;
    private static final long TOPLIST_DETAIL_TTL_SEC = 10L * 60L;
    private static final long PLAYLIST_LIST_TTL_SEC = 30L * 60L;
    private static final long PLAYLIST_RECOMMEND_TTL_SEC = 10L * 60L;
    private static final long PLAYLIST_DETAIL_TTL_SEC = 10L * 60L;
    private static final long ALBUM_DETAIL_TTL_SEC = 10L * 60L;
    private static final long ARTIST_WORKS_TTL_SEC = 10L * 60L;

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

    private final MusicCache cache;
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
    private final com.example.website.service.PerformanceSingleFlight singleFlight =
            new com.example.website.service.PerformanceSingleFlight();

    @Autowired
    public MusicService(MusicCache cache,
                        SysConfigService configService,
                        QqMusicClient qq,
                        NeteaseMusicClient netease,
                        KuwoMusicClient kuwo,
                        ChkszNeteaseMusicClient chkszNetease,
                        TuneFreePayClient tfPay,
                        QqTextFallbackClient qqTextFallback,
                        PlayUrlVerifier playUrlVerifier,
                        @Qualifier(OkHttpConfig.EXECUTOR_MUSIC_RESOLVER) Executor musicResolverExecutor) {
        this(cache, configService, qq, netease, kuwo, chkszNetease, tfPay, qqTextFallback,
                playUrlVerifier, musicResolverExecutor, DEFAULT_HEDGE_DELAY_MILLIS);
    }

    MusicService(MusicCache cache,
                 SysConfigService configService,
                 QqMusicClient qq,
                 NeteaseMusicClient netease,
                 KuwoMusicClient kuwo,
                 ChkszNeteaseMusicClient chkszNetease,
                 TuneFreePayClient tfPay,
                 QqTextFallbackClient qqTextFallback,
                 PlayUrlVerifier playUrlVerifier) {
        this(cache, configService, qq, netease, kuwo, chkszNetease, tfPay, qqTextFallback,
                playUrlVerifier, Runnable::run, 0L);
    }

    MusicService(MusicCache cache,
                 SysConfigService configService,
                 QqMusicClient qq,
                 NeteaseMusicClient netease,
                 KuwoMusicClient kuwo,
                 ChkszNeteaseMusicClient chkszNetease,
                 TuneFreePayClient tfPay,
                 QqTextFallbackClient qqTextFallback,
                 PlayUrlVerifier playUrlVerifier,
                 Executor musicResolverExecutor,
                 long hedgeDelayMillis) {
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
    }

    public SearchResultView search(String rawSource, String keyword, int page, int pageSize) {
        return search(rawSource, keyword, null, page, pageSize);
    }

    public SearchResultView search(String rawSource, String keyword, String rawType, int page, int pageSize) {
        MusicSource source = MusicSource.of(rawSource);
        MusicSearchType type = MusicSearchType.of(rawType);
        String kw = requireKeyword(keyword);
        int p = Math.max(1, page);
        int s = Math.min(30, Math.max(1, pageSize));

        String key = "music:search:" + source.getValue() + ":" + type.getValue() + ":" + kw + ":" + p + ":" + s;
        SearchResultView cached = cache.get(key, SearchResultView.class);
        if (cached != null) return cached;

        return singleFlight.execute(key, () -> {
            SearchResultView view;
            if (type == MusicSearchType.SONG) {
                List<SongSearchItem> list;
                switch (source) {
                    case QQ: list = qq.search(kw, p, s); break;
                    case NETEASE: list = netease.search(kw, p, s); break;
                    case KUWO: list = kuwo.search(kw, p, s); break;
                    default: throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
                }
                view = SearchResultView.songs(source, kw, p, s, null, list);
            } else {
                NeteaseMusicClient.CollectionSearchResult result;
                switch (source) {
                    case QQ: result = qq.searchCollections(kw, type, p, s); break;
                    case KUWO: result = kuwo.searchCollections(kw, type, p, s); break;
                    case NETEASE: result = netease.searchCollections(kw, type, p, s); break;
                    default: throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
                }
                List<SearchCollectionItem> items = result == null ? Collections.emptyList() : result.getList();
                Long total = result == null ? 0L : result.getTotal();
                view = SearchResultView.collections(source, type, kw, p, s, total, items);
            }
            cache.put(key, view, SEARCH_TTL_SEC);
            return view;
        });
    }

    public ToplistListView toplists(String rawSource) {
        MusicSource source = MusicSource.of(rawSource);
        String key = "music:toplist:" + source.getValue();
        ToplistListView cached = cache.get(key, ToplistListView.class);
        if (cached != null) return cached;

        List<ToplistItem> list;
        switch (source) {
            case QQ:
                list = qq.fetchToplists();
                break;
            case NETEASE:
                list = netease.fetchToplists();
                break;
            case KUWO:
                list = kuwo.fetchToplists();
                break;
            default:
                throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
        }

        ToplistListView view = new ToplistListView(source, list);
        cache.put(key, view, TOPLIST_LIST_TTL_SEC);
        return view;
    }

    public ToplistDetailView toplistDetail(String rawSource, String id, int page, int pageSize) {
        MusicSource source = MusicSource.of(rawSource);
        String sid = requireId(id);
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, pageSize));

        String key = "music:toplist:detail:" + source.getValue() + ":" + sid + ":" + p + ":" + s;
        ToplistDetailView cached = cache.get(key, ToplistDetailView.class);
        if (cached != null) return cached;

        ToplistDetailView view;
        switch (source) {
            case QQ:
                view = qq.fetchToplistDetail(sid, p, s);
                break;
            case NETEASE:
                view = netease.fetchToplistDetail(sid, p, s);
                break;
            case KUWO:
                view = kuwo.fetchToplistDetail(sid, p, s);
                break;
            default:
                throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
        }

        cache.put(key, view, TOPLIST_DETAIL_TTL_SEC);
        return view;
    }

    public PlaylistListView playlists(String rawSource,
                                      String category,
                                      String order,
                                      int page,
                                      int pageSize) {
        MusicSource source = MusicSource.of(rawSource);
        int p = Math.max(1, page);
        int s = Math.min(50, Math.max(1, pageSize));

        switch (source) {
            case QQ: {
                String cat = category == null ? null : category.trim();
                String ord = normaliseOrder(order);
                String key = "music:playlist:" + source.getValue() + ":" + (cat == null ? "" : cat) + ":" + ord + ":" + p + ":" + s;
                PlaylistListView cached = cache.get(key, PlaylistListView.class);
                if (cached != null) return cached;

                PlaylistListView view = qq.fetchPlaylists(cat, ord, p, s);
                cache.put(key, view, PLAYLIST_LIST_TTL_SEC);
                return view;
            }
            case NETEASE: {
                String cat = normaliseCategory(category);
                String ord = normaliseOrder(order);
                String key = "music:playlist:" + source.getValue() + ":" + cat + ":" + ord + ":" + p + ":" + s;
                PlaylistListView cached = cache.get(key, PlaylistListView.class);
                if (cached != null) return cached;

                PlaylistListView view = netease.fetchPlaylists(cat, ord, p, s);
                cache.put(key, view, PLAYLIST_LIST_TTL_SEC);
                return view;
            }
            case KUWO: {
                String key = "music:playlist:" + source.getValue() + ":" + p + ":" + s;
                PlaylistListView cached = cache.get(key, PlaylistListView.class);
                if (cached != null) return cached;

                PlaylistListView view = kuwo.fetchPlaylists(p, s);
                cache.put(key, view, PLAYLIST_LIST_TTL_SEC);
                return view;
            }
            default:
                throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
        }
    }

    public PlaylistListView recommendedPlaylists(String rawSource, int limit) {
        MusicSource source = MusicSource.of(rawSource);
        int size = Math.min(50, Math.max(1, limit));
        String key = "music:playlist:recommend:" + source.getValue() + ":" + size;
        PlaylistListView cached = cache.get(key, PlaylistListView.class);
        if (cached != null) return cached;

        PlaylistListView view;
        switch (source) {
            case QQ:
                view = qq.fetchRecommendedPlaylists(size);
                break;
            case NETEASE:
                view = netease.fetchRecommendedPlaylists(size);
                break;
            case KUWO:
                view = kuwo.fetchRecommendedPlaylists(size);
                break;
            default:
                throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
        }
        cache.put(key, view, PLAYLIST_RECOMMEND_TTL_SEC);
        return view;
    }

    public PlaylistDetailView playlistDetail(String rawSource, String id, int page, int pageSize) {
        MusicSource source = MusicSource.of(rawSource);
        String sid = requireId(id);
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, pageSize));

        String key = "music:playlist:detail:" + source.getValue() + ":" + sid + ":" + p + ":" + s;
        PlaylistDetailView cached = cache.get(key, PlaylistDetailView.class);
        if (cached != null) return cached;

        PlaylistDetailView view;
        switch (source) {
            case QQ:
                view = qq.fetchPlaylistDetail(sid, p, s);
                break;
            case NETEASE:
                view = netease.fetchPlaylistDetail(sid, p, s);
                break;
            case KUWO:
                view = kuwo.fetchPlaylistDetail(sid, p, s);
                break;
            default:
                throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
        }

        cache.put(key, view, PLAYLIST_DETAIL_TTL_SEC);
        return view;
    }

    public ToplistDetailView newSongs(String rawSource, int page, int pageSize) {
        MusicSource source = MusicSource.of(rawSource);
        String id;
        switch (source) {
            case NETEASE:
                id = "3779629";
                break;
            case QQ:
                id = "27";
                break;
            case KUWO:
                id = "17";
                break;
            default:
                throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
        }
        return toplistDetail(rawSource, id, page, pageSize);
    }

    public List<ArtistDetailView> getArtists(String rawSource, List<String> ids) {
        MusicSource source = MusicSource.of(rawSource);
        List<ArtistDetailView> result = new ArrayList<>();

        for (String id : ids) {
            try {
                ArtistDetailView detail = fetchArtistDetail(source, id);
                if (detail != null) {
                    result.add(detail);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch artist detail for {}:{}: {}", source, id, e.getMessage());
            }
        }

        return result;
    }

    public AlbumDetailView albumDetail(String rawSource, String id, int page, int pageSize) {
        MusicSource source = MusicSource.of(rawSource);
        String sid = requireId(id);
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, pageSize));
        String key = "music:album:detail:" + source.getValue() + ":" + sid + ":" + p + ":" + s;
        AlbumDetailView cached = cache.get(key, AlbumDetailView.class);
        if (cached != null) return cached;

        AlbumDetailView view;
        switch (source) {
            case QQ:
                view = qq.fetchAlbumDetail(sid, p, s);
                break;
            case NETEASE:
                view = netease.fetchAlbumDetail(sid, p, s);
                break;
            case KUWO:
                view = kuwo.fetchAlbumDetail(sid, p, s);
                break;
            default:
                throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
        }
        cache.put(key, view, ALBUM_DETAIL_TTL_SEC);
        return view;
    }

    public ArtistWorksView artistWorks(String rawSource, String id, String rawType, int page, int pageSize) {
        MusicSource source = MusicSource.of(rawSource);
        String sid = requireId(id);
        String type = rawType == null ? "song" : rawType.trim().toLowerCase(Locale.ROOT);
        if (!"song".equals(type) && !"album".equals(type)) {
            throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "type must be song or album");
        }
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, pageSize));
        String key = "music:artist:works:" + source.getValue() + ":" + sid + ":" + type + ":" + p + ":" + s;
        ArtistWorksView cached = cache.get(key, ArtistWorksView.class);
        if (cached != null) return cached;

        ArtistWorksView view;
        switch (source) {
            case QQ:
                view = qq.fetchArtistWorks(sid, type, p, s);
                break;
            case NETEASE:
                view = netease.fetchArtistWorks(sid, type, p, s);
                break;
            case KUWO:
                view = kuwo.fetchArtistWorks(sid, type, p, s);
                break;
            default:
                throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
        }
        cache.put(key, view, ARTIST_WORKS_TTL_SEC);
        return view;
    }

    private ArtistDetailView fetchArtistDetail(MusicSource source, String id) {
        switch (source) {
            case QQ:
                return qq.fetchArtistDetail(id);
            case NETEASE:
                return netease.fetchArtistDetail(id);
            case KUWO:
                return kuwo.fetchArtistDetail(id);
            default:
                return null;
        }
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
                logPlayResolution(source, quality, "cache", startedNanos, true, null);
                return response;
            }

            try {
                PlayInfo resolved = singleFlight.execute(key,
                        () -> resolvePlay(source, quality, sid, key),
                        MusicRequestBudget.remainingNanos(), TimeUnit.NANOSECONDS);
                logPlayResolution(source, quality, "upstream", startedNanos, false, null);
                return resolved;
            } catch (PerformanceSingleFlight.WaitTimeoutException ex) {
                MusicBusinessException timeout = new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT,
                        "music play resolution exceeded its time budget", ex);
                logPlayResolution(source, quality, "failed", startedNanos, false, timeout);
                throw timeout;
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
        ExecutorCompletionService<PlayInfo> completions = new ExecutorCompletionService<>(musicResolverExecutor);
        Map<Future<PlayInfo>, PlayResolver> active = new HashMap<>();
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

                PlayResolver resolver = active.remove(completed);
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
                                Map<Future<PlayInfo>, PlayResolver> active,
                                PlayResolver resolver,
                                MusicSource source,
                                PlaybackQualityRequest quality,
                                String sid,
                                SongMetaLookup meta,
                                long deadlineNanos) {
        Future<PlayInfo> future = completions.submit(() -> {
            try (MusicRequestBudget.Scope ignored = MusicRequestBudget.useDeadline(deadlineNanos)) {
                return attemptResolver(resolver, source, quality, sid, meta);
            }
        });
        active.put(future, resolver);
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
        if (source == MusicSource.QQ) return configured;
        List<PlayResolver> filtered = new ArrayList<>();
        for (PlayResolver resolver : configured) {
            if (resolver != PlayResolver.QQ_TEXT) filtered.add(resolver);
        }
        return filtered;
    }

    private static void cancelAll(Map<Future<PlayInfo>, PlayResolver> active) {
        for (Future<PlayInfo> future : active.keySet()) {
            future.cancel(true);
        }
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
            if (other == requested) continue;
            MusicRequestBudget.ensureRemaining();
            try {
                List<SongSearchItem> hits = searchOn(other, keyword, 1, 3);
                SongSearchItem pick = pickMatch(hits, meta);
                if (pick == null) continue;

                PlayInfo alt = tfPay.play(other, pick.getId(), quality);
                if (alt == null) {
                    throw new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL,
                            "no playable url for " + other.getValue() + ":" + pick.getId());
                }
                alt.setSource(requested);
                alt.setId(requestedId);
                alt.setActualSource(other);
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

    private SongSearchItem pickMatch(List<SongSearchItem> hits, SongSearchItem target) {
        if (hits == null || hits.isEmpty()) return null;
        String targetName = normalise(target.getName());
        String targetArtist = normaliseArtist(target.getArtist());
        for (SongSearchItem h : hits) {
            if (!normalise(h.getName()).equals(targetName)) continue;
            if (targetArtist.isEmpty() || targetArtist.equals(normaliseArtist(h.getArtist()))) {
                return h;
            }
        }
        return null;
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

    public LyricView lyric(String rawSource, String id) {
        MusicSource source = MusicSource.of(rawSource);
        String sid = requireId(id);

        String officialKey = "music:lyric:official:v3:" + source.getValue() + ":" + sid;
        LyricView cachedOfficial = cache.get(officialKey, LyricView.class);
        if (cachedOfficial != null) return cachedOfficial;

        MusicBusinessException officialError;
        try {
            LyricView official = fetchOfficialLyric(source, sid);
            // Retry missing word timing soon: a transient enhanced-endpoint failure
            // must not hide recovered word lyrics for the full six-hour cache TTL.
            cache.put(officialKey, official, isEmpty(official.getKaraokeLyrics())
                    ? LYRIC_FALLBACK_TTL_SEC : LYRIC_TTL_SEC);
            return official;
        } catch (MusicBusinessException ex) {
            officialError = ex;
            log.debug("Official lyric failed for {}/{}: {}", source.getValue(), sid, ex.getMessage());
        }

        String fallbackKey = "music:lyric:fallback:v3:" + source.getValue() + ":" + sid;
        LyricView cachedFallback = cache.get(fallbackKey, LyricView.class);
        if (cachedFallback != null) return cachedFallback;
        try {
            LyricView fallback = fetchFallbackLyric(source, sid);
            if (fallback != null) {
                cache.put(fallbackKey, fallback, LYRIC_FALLBACK_TTL_SEC);
                return fallback;
            }
        } catch (MusicBusinessException ex) {
            log.debug("Fallback lyric failed for {}/{}: {}", source.getValue(), sid, ex.getMessage());
        }
        throw officialError;
    }

    private SongSearchItem ensureMeta(MusicSource source, String id, SongSearchItem current) {
        return current != null ? current : fetchSongInfoOn(source, id);
    }

    private LyricView fetchOfficialLyric(MusicSource source, String id) {
        switch (source) {
            case QQ: {
                LyricInfo lyric = qq.fetchLyrics(id);
                if (lyric != null && (!isEmpty(lyric.getLineLyrics()) || !isEmpty(lyric.getKaraokeLyrics()))) {
                    return new LyricView(id, source, lyric.getLineLyrics(), lyric.getKaraokeLyrics(), "qq", Boolean.TRUE);
                }
                throw new MusicBusinessException(MusicErrorCode.UPSTREAM_LYRIC_FAILED,
                        "no lyric from qq for " + id);
            }
            case NETEASE: {
                String[] both = netease.fetchLyric(id);
                if ((both[0] == null || both[0].isEmpty()) && (both[1] == null || both[1].isEmpty())) {
                    throw new MusicBusinessException(MusicErrorCode.UPSTREAM_LYRIC_FAILED,
                            "no lyric from netease for " + id);
                }
                return new LyricView(id, source, both[0], both[1], "netease", Boolean.TRUE);
            }
            case KUWO: {
                LyricInfo lyric = kuwo.fetchLyrics(id);
                if (lyric != null && (!isEmpty(lyric.getLineLyrics()) || !isEmpty(lyric.getKaraokeLyrics()))) {
                    return new LyricView(id, source, lyric.getLineLyrics(), lyric.getKaraokeLyrics(), "kuwo", Boolean.TRUE);
                }
                throw new MusicBusinessException(MusicErrorCode.UPSTREAM_LYRIC_FAILED,
                        "no lyric from kuwo for " + id);
            }
            default:
                throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
        }
    }

    private LyricView fetchFallbackLyric(MusicSource source, String id) {
        switch (source) {
            case QQ: {
                SongSearchItem meta = qq.fetchSongInfo(id);
                if (meta == null || isEmpty(meta.getName())) return null;
                LyricInfo lyric = qqTextFallback.fetchLyric(meta);
                if (lyric == null || (isEmpty(lyric.getLineLyrics()) && isEmpty(lyric.getKaraokeLyrics()))) {
                    return null;
                }
                return new LyricView(id, source, lyric.getLineLyrics(), lyric.getKaraokeLyrics(),
                        "cyapi", Boolean.FALSE);
            }
            case KUWO: {
                PlayInfo play = tfPay.play(source, id, MusicQuality.FLAC);
                LyricInfo lyric = play == null ? null : play.getLyric();
                if (lyric == null || (isEmpty(lyric.getLineLyrics()) && isEmpty(lyric.getKaraokeLyrics()))) {
                    return null;
                }
                return new LyricView(id, source, lyric.getLineLyrics(), lyric.getKaraokeLyrics(),
                        "tunefree", Boolean.FALSE);
            }
            default:
                return null;
        }
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
        PlayInfo p = new PlayInfo();
        p.setId(src.getId());
        p.setSource(src.getSource());
        p.setActualSource(src.getActualSource());
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
        p.setFromCache(Boolean.TRUE);
        p.setLyric(src.getLyric());
        return p;
    }

    private static String requireKeyword(String kw) {
        if (kw == null || kw.trim().isEmpty()) {
            throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "keyword is required");
        }
        return kw.trim();
    }

    private static String requireId(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new MusicBusinessException(MusicErrorCode.SONG_NOT_FOUND, "id is required");
        }
        return id.trim();
    }

    private static String normaliseCategory(String category) {
        if (category == null || category.trim().isEmpty()) return "\u5168\u90E8";
        return category.trim();
    }

    private static String normaliseOrder(String order) {
        if (order == null || order.trim().isEmpty()) return "hot";
        return order.trim();
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
