package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.MusicSearchType;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.SearchCollectionItem;
import com.example.website.module.music.api.dto.SearchResultView;
import com.example.website.module.music.api.dto.SongSearchItem;
import com.example.website.shared.concurrent.PerformanceSingleFlight;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@lombok.RequiredArgsConstructor
public class MusicSearchService {
    private static final long SEARCH_TTL_SEC = 60L;
    private final MusicCache cache;
    private final QqMusicClient qq;
    private final NeteaseMusicClient netease;
    private final KuwoMusicClient kuwo;
    private final QishuiMusicClient qishui;
    private final PerformanceSingleFlight singleFlight = new PerformanceSingleFlight();
    private final MusicMetrics metrics;

    public SearchResultView search(String rawSource, String keyword, int page, int pageSize) {
        return search(rawSource, keyword, null, page, pageSize);
    }

    public SearchResultView search(String rawSource, String keyword, String rawType, int page, int pageSize) {
        MusicSource source = MusicSource.of(rawSource);
        long started = System.nanoTime();
        String outcome = "failure";
        try (MusicRequestBudget.Scope scope = MusicRequestBudget.start(8, TimeUnit.SECONDS)) {
            SearchResultView result = searchWithinBudget(source, keyword, rawType, page, pageSize);
            outcome = "success";
            return result;
        } catch (PerformanceSingleFlight.WaitTimeoutException ex) {
            outcome = "timeout";
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT);
        } finally {
            metrics.record(source.getValue(), "search", outcome, started);
        }
    }

    private SearchResultView searchWithinBudget(MusicSource source, String keyword, String rawType, int page, int pageSize) {
        MusicSearchType type = MusicSearchType.of(rawType);
        String kw = requireKeyword(keyword);
        int p = Math.max(1, page);
        int s = Math.min(30, Math.max(1, pageSize));

        String key = "music:search:" + source.getValue() + ":" + type.getValue() + ":" + kw + ":" + p + ":" + s;
        SearchResultView cached = cache.get(key, SearchResultView.class);
        metrics.cache(source.getValue(), "search", cached != null);
        if (cached != null) return cached;

        return singleFlight.execute(key, () -> {
            SearchResultView completed = cache.get(key, SearchResultView.class);
            if (completed != null) return completed;
            SearchResultView view;
            if (type == MusicSearchType.SONG) {
                List<SongSearchItem> list;
                switch (source) {
                    case QISHUI: list = qishui.search(kw, p, s); break;
                    case QQ: list = qq.search(kw, p, s); break;
                    case NETEASE: list = netease.search(kw, p, s); break;
                    case KUWO: list = kuwo.search(kw, p, s); break;
                    default: throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
                }
                view = SearchResultView.songs(source, kw, p, s, null, list);
            } else {
                NeteaseMusicClient.CollectionSearchResult result;
                switch (source) {
                    case QISHUI: result = qishui.searchCollections(kw, type, p, s); break;
                    case QQ: result = qq.searchCollections(kw, type, p, s); break;
                    case KUWO: result = kuwo.searchCollections(kw, type, p, s); break;
                    case NETEASE: result = netease.searchCollections(kw, type, p, s); break;
                    default: throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
                }
                List<SearchCollectionItem> items = result == null ? Collections.emptyList() : result.getList();
                Long total = result == null ? Long.valueOf(0L) : result.getTotal();
                view = SearchResultView.collections(source, type, kw, p, s, total, items);
            }
            MusicRequestBudget.ensureRemaining();
            cache.put(key, view, SEARCH_TTL_SEC);
            return view;
        }, MusicRequestBudget.remainingNanos(), TimeUnit.NANOSECONDS);
    }

    private static String requireKeyword(String kw) {
        if (kw == null || kw.trim().isEmpty()) {
            throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "keyword is required");
        }
        return kw.trim();
    }

}
