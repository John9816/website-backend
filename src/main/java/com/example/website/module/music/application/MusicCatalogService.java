package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.ArtistDetailView;
import com.example.website.module.music.api.dto.ArtistWorksView;
import com.example.website.module.music.api.dto.AlbumDetailView;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.PlaylistDetailView;
import com.example.website.module.music.api.dto.PlaylistListView;
import com.example.website.module.music.api.dto.ToplistDetailView;
import com.example.website.module.music.api.dto.ToplistItem;
import com.example.website.module.music.api.dto.ToplistListView;
import com.example.website.shared.concurrent.PerformanceSingleFlight;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class MusicCatalogService {
    private static final long TOPLIST_LIST_TTL_SEC = 30L * 60L;
    private static final long TOPLIST_DETAIL_TTL_SEC = 10L * 60L;
    private static final long PLAYLIST_LIST_TTL_SEC = 30L * 60L;
    private static final long PLAYLIST_RECOMMEND_TTL_SEC = 10L * 60L;
    private static final long PLAYLIST_DETAIL_TTL_SEC = 10L * 60L;
    private static final long ALBUM_DETAIL_TTL_SEC = 10L * 60L;
    private static final long ARTIST_WORKS_TTL_SEC = 10L * 60L;

    private final MusicCache cache;
    private final QqMusicClient qq;
    private final NeteaseMusicClient netease;
    private final KuwoMusicClient kuwo;
    private final QishuiMusicClient qishui;
    private final PerformanceSingleFlight singleFlight = new PerformanceSingleFlight();

    public ToplistListView toplists(String rawSource) {
        MusicSource source = MusicSource.of(rawSource);
        String key = "music:toplist:" + source.getValue();
        return cached(key, ToplistListView.class, TOPLIST_LIST_TTL_SEC, () -> {
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
                case QISHUI:
                    list = qishui.discoveryToplists();
                    break;
                default:
                    throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
            }

            ToplistListView view = new ToplistListView(source, list);
            return view;
        });
    }

    public ToplistDetailView toplistDetail(String rawSource, String id, int page, int pageSize) {
        MusicSource source = MusicSource.of(rawSource);
        String sid = requireId(id);
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, pageSize));

        String key = "music:toplist:detail:" + source.getValue() + ":" + sid + ":" + p + ":" + s;
        return cached(key, ToplistDetailView.class, TOPLIST_DETAIL_TTL_SEC, () -> {
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
                case QISHUI:
                    view = qishui.discoveryToplistDetail(sid, p, s);
                    break;
                default:
                    throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
            }
            return view;
        });
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
                return cached(key, PlaylistListView.class, PLAYLIST_LIST_TTL_SEC, () -> {
                    PlaylistListView view = qq.fetchPlaylists(cat, ord, p, s);
                    return view;
                });
            }
            case NETEASE: {
                String cat = normaliseCategory(category);
                String ord = normaliseOrder(order);
                String key = "music:playlist:" + source.getValue() + ":" + cat + ":" + ord + ":" + p + ":" + s;
                return cached(key, PlaylistListView.class, PLAYLIST_LIST_TTL_SEC, () -> {
                    PlaylistListView view = netease.fetchPlaylists(cat, ord, p, s);
                    return view;
                });
            }
            case KUWO: {
                String key = "music:playlist:" + source.getValue() + ":" + p + ":" + s;
                return cached(key, PlaylistListView.class, PLAYLIST_LIST_TTL_SEC, () -> {
                    PlaylistListView view = kuwo.fetchPlaylists(p, s);
                    return view;
                });
            }
            case QISHUI: {
                String cat = (category == null || category.trim().isEmpty()) ? "热门" : category.trim();
                String ord = normaliseOrder(order);
                String key = "music:playlist:" + source.getValue() + ":" + cat + ":" + ord + ":" + p + ":" + s;
                return cached(key, PlaylistListView.class, PLAYLIST_LIST_TTL_SEC, () ->
                        new PlaylistListView(source, cat, ord, p, s, null,
                                qishui.discoverPlaylists(cat, s)));
            }
            default:
                throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
        }
    }

    public PlaylistListView recommendedPlaylists(String rawSource, int limit) {
        MusicSource source = MusicSource.of(rawSource);
        int size = Math.min(50, Math.max(1, limit));
        String key = "music:playlist:recommend:" + source.getValue() + ":" + size;
        return cached(key, PlaylistListView.class, PLAYLIST_RECOMMEND_TTL_SEC, () -> {
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
                case QISHUI:
                    view = new PlaylistListView(source, null, "hot", 1, size, null,
                            qishui.discoveryPlaylists(size));
                    break;
                default:
                    throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
            }
            return view;
        });
    }

    public PlaylistDetailView playlistDetail(String rawSource, String id, int page, int pageSize) {
        MusicSource source = MusicSource.of(rawSource);
        String sid = requireId(id);
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, pageSize));

        String key = "music:playlist:detail:" + source.getValue() + ":" + sid + ":" + p + ":" + s;
        return cached(key, PlaylistDetailView.class, PLAYLIST_DETAIL_TTL_SEC, () -> {
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
                case QISHUI:
                    view = qishui.fetchPlaylistDetail(sid, p, s);
                    break;
                default:
                    throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
            }
            return view;
        });
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
            case QISHUI:
                id = "新歌";
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
        return cached(key, AlbumDetailView.class, ALBUM_DETAIL_TTL_SEC, () -> {
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
                case QISHUI:
                    view = qishui.fetchAlbumDetail(sid, p, s);
                    break;
                default:
                    throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
            }
            return view;
        });
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
        return cached(key, ArtistWorksView.class, ARTIST_WORKS_TTL_SEC, () -> {
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
                case QISHUI:
                    view = qishui.fetchArtistWorks(sid, type, p, s);
                    break;
                default:
                    throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "unsupported source");
            }
            return view;
        });
    }

    private ArtistDetailView fetchArtistDetail(MusicSource source, String id) {
        switch (source) {
            case QISHUI:
                return qishui.fetchArtistDetail(id);
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

    private <T> T cached(String key, Class<T> type, long ttlSeconds, java.util.function.Supplier<T> loader) {
        T value = cache.get(key, type);
        if (value != null) return value;
        return singleFlight.execute(key, () -> {
            T completed = cache.get(key, type);
            if (completed != null) return completed;
            T loaded = loader.get();
            cache.put(key, loaded, ttlSeconds);
            return loaded;
        });
    }
}
