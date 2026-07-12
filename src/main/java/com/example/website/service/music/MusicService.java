package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
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
import com.example.website.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class MusicService {

    private static final long SEARCH_TTL_SEC = 60L;
    private static final long PLAY_TTL_SEC = 1200L;
    private static final long LYRIC_TTL_SEC = 6L * 60L * 60L;
    private static final long TOPLIST_LIST_TTL_SEC = 30L * 60L;
    private static final long TOPLIST_DETAIL_TTL_SEC = 10L * 60L;
    private static final long PLAYLIST_LIST_TTL_SEC = 30L * 60L;
    private static final long PLAYLIST_DETAIL_TTL_SEC = 10L * 60L;

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
    private final TuneFreePayClient tfPay;
    private final QqTextFallbackClient qqTextFallback;
    private final PlayUrlVerifier playUrlVerifier;

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

        SearchResultView view;
        if (type == MusicSearchType.SONG) {
            List<SongSearchItem> list;
            switch (source) {
                case QQ:
                    list = qq.search(kw, p, s);
                    break;
                case NETEASE:
                    list = netease.search(kw, p, s);
                    break;
                case KUWO:
                    list = kuwo.search(kw, p, s);
                    break;
                default:
                    throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
            }
            view = SearchResultView.songs(source, kw, p, s, null, list);
        } else {
            if (source != MusicSource.NETEASE) {
                view = SearchResultView.collections(source, type, kw, p, s, 0L, Collections.emptyList());
            } else {
                NeteaseMusicClient.CollectionSearchResult result = netease.searchCollections(kw, type, p, s);
                List<SearchCollectionItem> items = result == null ? Collections.emptyList() : result.getList();
                Long total = result == null ? 0L : result.getTotal();
                view = SearchResultView.collections(source, type, kw, p, s, total, items);
            }
        }
        cache.put(key, view, SEARCH_TTL_SEC);
        return view;
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

    public PlayInfo play(String rawSource, String id, String rawQuality) {
        MusicSource source = MusicSource.of(rawSource);
        MusicQuality quality = MusicQuality.of(rawQuality);
        String sid = requireId(id);

        String key = "music:play:" + source.getValue() + ":" + sid + ":" + quality.getValue();
        PlayInfo cached = cache.get(key, PlayInfo.class);
        if (cached != null) {
            if (acceptCachedPlayInfo(key, source, sid, cached)) {
                return copyWithFromCache(cached);
            }
        }

        PlayInfo info;
        MusicBusinessException originalError = null;
        SongSearchItem meta = null;

        for (PlayResolver resolver : resolvePlayResolverOrder()) {
            switch (resolver) {
                case PRIMARY:
                    try {
                        info = verifyPlayableOrThrow(source, sid, playWithQualityFallback(source, sid, quality),
                                "primary resolver");
                        info.setRequestedQuality(quality.getValue());
                        cache.put(key, info, PLAY_TTL_SEC);
                        return info;
                    } catch (MusicBusinessException ex) {
                        originalError = ex;
                    }
                    break;
                case QQ_TEXT:
                    if (source != MusicSource.QQ) {
                        break;
                    }
                    meta = ensureMeta(source, sid, meta);
                    info = verifyPlayableOrNull(source, sid, tryQqTextFallback(source, sid, quality, meta),
                            "qq text fallback");
                    if (info != null) {
                        info.setRequestedQuality(quality.getValue());
                        cache.put(key, info, PLAY_TTL_SEC);
                        return info;
                    }
                    break;
                case CROSS_SOURCE:
                    meta = ensureMeta(source, sid, meta);
                    try {
                        info = tryFallback(source, sid, quality, meta, originalError);
                        info.setRequestedQuality(quality.getValue());
                        cache.put(key, info, PLAY_TTL_SEC);
                        return info;
                    } catch (MusicBusinessException ex) {
                        if (originalError == null) {
                            originalError = ex;
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        if (originalError != null) {
            throw originalError;
        }
        throw new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL,
                "no playable url for " + source.getValue() + ":" + sid);
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
            try {
                PlayInfo info = tfPay.play(source, id, q);
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
            try {
                List<SongSearchItem> hits = searchOn(other, keyword, 1, 3);
                SongSearchItem pick = pickMatch(hits, meta);
                if (pick == null) continue;

                PlayInfo alt = tfPay.play(other, pick.getId(), quality);
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
        for (SongSearchItem h : hits) {
            if (normalise(h.getName()).equals(targetName)) return h;
        }
        return hits.get(0);
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

        String lyricKey = "music:lyric:" + source.getValue() + ":" + sid;
        LyricView cached = cache.get(lyricKey, LyricView.class);
        if (cached != null) return cached;

        LyricInfo fromPlay = findLyricInPlayCache(source, sid);
        if (fromPlay != null && (fromPlay.getLineLyrics() != null || fromPlay.getKaraokeLyrics() != null)) {
            LyricView view = new LyricView(sid, source, fromPlay.getLineLyrics(), fromPlay.getKaraokeLyrics());
            cache.put(lyricKey, view, LYRIC_TTL_SEC);
            return view;
        }

        LyricView upstream = fetchLyricFromUpstream(source, sid);
        cache.put(lyricKey, upstream, LYRIC_TTL_SEC);
        return upstream;
    }

    private LyricInfo findLyricInPlayCache(MusicSource source, String id) {
        for (MusicQuality q : MusicQuality.values()) {
            PlayInfo p = cache.get("music:play:" + source.getValue() + ":" + id + ":" + q.getValue(), PlayInfo.class);
            if (p != null && p.getLyric() != null) return p.getLyric();
        }
        return null;
    }

    private SongSearchItem ensureMeta(MusicSource source, String id, SongSearchItem current) {
        return current != null ? current : fetchSongInfoOn(source, id);
    }

    private LyricView fetchLyricFromUpstream(MusicSource source, String id) {
        switch (source) {
            case QQ: {
                String line = null;
                MusicBusinessException officialError = null;
                try {
                    line = qq.fetchLyric(id);
                } catch (MusicBusinessException ex) {
                    officialError = ex;
                    log.debug("QQ official lyric failed for {}: {}", id, ex.getMessage());
                }
                if (!isEmpty(line)) {
                    return new LyricView(id, source, line, null);
                }
                SongSearchItem meta = qq.fetchSongInfo(id);
                if (meta != null && !isEmpty(meta.getName())) {
                    try {
                        LyricInfo lyric = qqTextFallback.fetchLyric(meta);
                        if (lyric != null && !isEmpty(lyric.getLineLyrics())) {
                            return new LyricView(id, source, lyric.getLineLyrics(), lyric.getKaraokeLyrics());
                        }
                    } catch (MusicBusinessException ex) {
                        log.debug("QQ lyric text fallback failed for {}: {}", id, ex.getMessage());
                    }
                }
                if (officialError != null) {
                    throw officialError;
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
                return new LyricView(id, source, both[0], both[1]);
            }
            case KUWO: {
                PlayInfo p = tfPay.play(source, id, MusicQuality.FLAC);
                cache.put("music:play:" + source.getValue() + ":" + id + ":" + MusicQuality.FLAC.getValue(), p, PLAY_TTL_SEC);
                LyricInfo lyric = p.getLyric();
                if (lyric == null || (isEmpty(lyric.getLineLyrics()) && isEmpty(lyric.getKaraokeLyrics()))) {
                    throw new MusicBusinessException(MusicErrorCode.UPSTREAM_LYRIC_FAILED,
                            "no lyric from kuwo for " + id);
                }
                return new LyricView(id, source, lyric.getLineLyrics(), lyric.getKaraokeLyrics());
            }
            default:
                throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
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
        String configured = DEFAULT_PLAY_RESOLVER_ORDER;
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
        String configured = DEFAULT_CROSS_SOURCE_ORDER;
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
