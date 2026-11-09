package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.AlbumDetailView;
import com.example.website.module.music.api.dto.AlbumSummaryItem;
import com.example.website.module.music.api.dto.ArtistDetailView;
import com.example.website.module.music.api.dto.ArtistWorksView;
import com.example.website.module.music.api.dto.LyricInfo;
import com.example.website.module.music.api.dto.MusicSearchType;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.PlaylistDetailView;
import com.example.website.module.music.api.dto.PlaylistItem;
import com.example.website.module.music.api.dto.ToplistDetailView;
import com.example.website.module.music.api.dto.ToplistItem;
import com.example.website.module.music.api.dto.SearchCollectionItem;
import com.example.website.module.music.api.dto.SongSearchItem;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.example.website.shared.concurrent.PerformanceSingleFlight;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.*;
import java.util.stream.Collectors;

@Component
public class QishuiMusicClient {
    private final QishuiHttpClient http;
    private final ObjectMapper mapper;
    @Autowired private QishuiShareClient shares;
    private final Cache<String, List<String>> suggestions = Caffeine.newBuilder()
            .maximumSize(1000).expireAfterWrite(60, TimeUnit.SECONDS).build();
    private final String deviceId = UUID.randomUUID().toString().replace("-", "");
    private final ReentrantLock tokenLock = new ReentrantLock();
    private final AtomicReference<CatalogToken> clientToken = new AtomicReference<>();
    private final Cache<String, JsonNode> mediaCache;
    private final PerformanceSingleFlight mediaFlights = new PerformanceSingleFlight();

    private static final class CatalogToken {
        final String value;
        final long expiresAt;
        CatalogToken(String value, long expiresAt) { this.value = value; this.expiresAt = expiresAt; }
        boolean valid() { return System.currentTimeMillis() < expiresAt; }
    }

    @Autowired
    public QishuiMusicClient(QishuiHttpClient http, ObjectMapper mapper) {
        this(http, mapper, Ticker.systemTicker());
    }

    QishuiMusicClient(QishuiHttpClient http, ObjectMapper mapper, Ticker ticker) {
        this.http = http;
        this.mapper = mapper;
        // /media/mget contains both song metadata and lyrics. Bound serialized payload weight
        // as well as TTL; entries are public catalog data and are never returned as mutable JSON.
        this.mediaCache = Caffeine.newBuilder().maximumWeight(16 * 1024 * 1024)
                .weigher((String key, JsonNode value) -> value.toString().getBytes(StandardCharsets.UTF_8).length)
                .expireAfterWrite(5, TimeUnit.MINUTES).ticker(ticker).build();
    }

    private CatalogToken token() {
        CatalogToken cached = clientToken.get();
        if (cached != null && cached.valid()) return cached;
        MusicRequestBudget.ensureRemaining();
        try {
            if (!tokenLock.tryLock(
                    Math.min(MusicRequestBudget.remainingNanos(), TimeUnit.SECONDS.toNanos(5)),
                    TimeUnit.NANOSECONDS))
                throw new MusicBusinessException(MusicErrorCode.SERVICE_BUSY);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT);
        }
        try {
            cached = clientToken.get();
            if (cached != null && cached.valid()) return cached;
            Map<String, String> q = QishuiHttpClient.common(deviceId);
            q.put("trigger_type", "cold_start");
            JsonNode r =
                    QishuiHttpClient.checked(
                            http.get(QishuiHttpClient.OPEN + "/client/token/get/", q, null).json);
            String token = r.path("client_token").asText("");
            if (token.isEmpty()) throw QishuiHttpClient.failure();
            long now = System.currentTimeMillis();
            long expiry = r.path("expires_time").asLong(0);
            if (expiry <= now / 1000 + 30) throw QishuiHttpClient.failure();
            CatalogToken fresh = new CatalogToken(token, now + Math.min(3600, expiry - now / 1000 - 30) * 1000);
            clientToken.set(fresh);
            return fresh;
        } finally {
            tokenLock.unlock();
        }
    }

    JsonNode post(String path, ObjectNode body) {
        try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(8, TimeUnit.SECONDS)) {
            return postWithinBudget(path, body);
        }
    }

    private JsonNode postWithinBudget(String path, ObjectNode body) {
        return postWithinBudget(path, body, deviceId, null);
    }

    JsonNode postFeed(String path, ObjectNode body, QishuiCredential credential) {
        if (!"/feed/song-tab/".equals(path) && !"/feed/mode/".equals(path))
            throw new IllegalArgumentException("Unsupported feed path");
        try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(8, TimeUnit.SECONDS)) {
            return postWithinBudget(path, body,
                    credential == null ? deviceId : credential.getDeviceId(),
                    credential == null ? null : credential.getUserId());
        }
    }

    private JsonNode postWithinBudget(String path, ObjectNode original, String requestDevice, String userId) {
        ObjectNode body = original.deepCopy();
        ObjectNode common = body.putObject("common_params");
        if (userId != null) common.put("user_id", userId);
        CatalogToken used = token();
        JsonNode r =
                http.post(
                                QishuiHttpClient.PLATFORM + path,
                                QishuiHttpClient.common(requestDevice),
                                body,
                                Collections.singletonMap("access-token", used.value))
                        .json;
        long code = r.path("err_no").asLong(0);
        // A late failure may invalidate only the exact generation used by that request.
        if (code == 28001003 || code == 28001008) clientToken.compareAndSet(used, null);
        return QishuiHttpClient.checked(r);
    }

    public List<String> searchSuggestions(String keyword) {
        if (keyword == null || keyword.length() > 120) throw new BusinessException(400, "Search keyword is too long");
        String q = keyword.trim();
        if (q.isEmpty()) return Collections.emptyList();
        return suggestions.get(q, key -> {
            JsonNode result = post("/search/sug/", mapper.createObjectNode().put("q", key));
            if (!result.path("sugs").isArray()) throw QishuiHttpClient.failure();
            Set<String> unique = new LinkedHashSet<>();
            for (JsonNode item : result.path("sugs")) {
                String text = item.path("suggestion").asText("").trim();
                if (!text.isEmpty() && text.length() <= 120) unique.add(text);
                if (unique.size() == 10) break;
            }
            return Collections.unmodifiableList(new ArrayList<>(unique));
        });
    }

    public String resolveShareUrl(String text) { return shares.resolve(text); }

    public List<SongSearchItem> search(String keyword, int page, int pageSize) {
        return searchEntities(keyword, "track", page, pageSize).stream()
                .map(e -> track(e.path("track")))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public NeteaseMusicClient.CollectionSearchResult searchCollections(
            String keyword, MusicSearchType type, int page, int pageSize) {
        List<SearchCollectionItem> list;
        switch (type) {
            case PLAYLIST:
                list = searchEntities(keyword, "playlist", page, pageSize).stream()
                        .map(e -> playlist(e.path("playlist")))
                        .collect(Collectors.toList());
                break;
            case ARTIST:
                list = searchEntities(keyword, "artist", page, pageSize).stream()
                        .map(e -> artist(e.path("artist")))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                break;
            case ALBUM:
                list = searchAlbums(keyword, page, pageSize);
                break;
            default:
                throw unsupported("unsupported collection search type");
        }
        return new NeteaseMusicClient.CollectionSearchResult(null, list);
    }

    private List<JsonNode> searchEntities(String keyword, String type, int page, int size) {
        if (page < 1 || page > 1000 || size < 1 || size > 100)
            throw new BusinessException(400, "Invalid Qishui pagination");
        try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(8, TimeUnit.SECONDS)) {
            String cursor = Long.toString((long) (page - 1) * size),
                    searchId = UUID.randomUUID().toString();
            List<JsonNode> result = new ArrayList<>();
            Set<String> cursors = new HashSet<>();
            for (int attempt = 0; attempt < 5 && result.size() < size; attempt++) {
                if (!cursors.add(cursor)) throw QishuiHttpClient.failure();
                ObjectNode b =
                        mapper.createObjectNode()
                                .put("q", keyword)
                                .put("cursor", cursor)
                                .put("search_id", searchId)
                                .put("from_search_id", searchId)
                                .put("search_method", "input")
                                .put("search_type", type)
                                .put("search_scene", "normal")
                                .put("scene_name", "search")
                                .put("ab_param", "");
                b.set("last_viewed_media", mapper.createObjectNode());
                JsonNode groups = post("/search/", b).path("result_groups");
                if (!groups.isArray()) throw QishuiHttpClient.failure();
                JsonNode group = null;
                for (JsonNode g : groups) if ((type + "s").equals(g.path("id").asText())) group = g;
                if (group == null) break;
                if (!group.path("data").isArray()) throw QishuiHttpClient.failure();
                for (JsonNode e : group.path("data")) {
                    if (result.size() == size) break;
                    result.add(e.path("entity"));
                }
                if (!group.path("has_more").asBoolean()) break;
                cursor = group.path("next_cursor").asText("");
                if (cursor.isEmpty()) throw QishuiHttpClient.failure();
            }
            return result;
        }
    }

    private JsonNode media(String id) {
        try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(8, TimeUnit.SECONDS)) {
            MusicRequestBudget.ensureRemaining();
            JsonNode cached = mediaCache.getIfPresent(id);
            if (cached != null) return cached;
            return mediaFlights.execute(id, () -> {
                JsonNode ready = mediaCache.getIfPresent(id);
                if (ready != null) return ready;
                JsonNode loaded = loadMedia(id);
                MusicRequestBudget.ensureRemaining();
                mediaCache.put(id, loaded);
                return loaded;
            }, MusicRequestBudget.remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (PerformanceSingleFlight.WaitTimeoutException ex) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT);
        }
    }

    private JsonNode loadMedia(String id) {
        ObjectNode b = mapper.createObjectNode();
        b.putArray("media_list").addObject().put("id", id).put("type", "track");
        b.set("card_context", mapper.createObjectNode());
        JsonNode list = post("/media/mget/", b).path("media_list");
        if (!list.isArray()) throw QishuiHttpClient.failure();
        for (JsonNode item : list) {
            JsonNode t = item.path("track_entity");
            if (id.equals(t.path("base_info").path("id").asText())) return t;
        }
        throw new MusicBusinessException(MusicErrorCode.SONG_NOT_FOUND);
    }

    public SongSearchItem fetchSongInfo(String id) {
        return track(media(id));
    }

    public LyricInfo fetchLyrics(String id) {
        JsonNode snapshot = media(id);
        try {
            JsonNode entity = snapshot.path("display_info").path("lyric_info").path("lyric_entity");
            String content = entity.path("content").asText("");
            if ("krc".equals(entity.path("type").asText())) return krc(content);
            return new LyricInfo(content.isEmpty() ? null : content, null);
        } catch (MusicBusinessException ex) {
            // A malformed lyric must not poison subsequent retries, nor remove a newer load.
            mediaCache.asMap().remove(id, snapshot);
            throw ex;
        }
    }

    static LyricInfo krc(String content) {
        try {
            return parseKrc(content);
        } catch (NumberFormatException | ArithmeticException ex) {
            throw QishuiHttpClient.failure();
        }
    }

    private static LyricInfo parseKrc(String content) {
        StringBuilder lrc = new StringBuilder(), yrc = new StringBuilder();
        Pattern line = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$"),
                word = Pattern.compile("<(\\d+),(\\d+),\\d+>([^<]*)");
        for (String row : content.split("\\r?\\n")) {
            Matcher lm = line.matcher(row);
            if (!lm.matches()) continue;
            long start = Long.parseLong(lm.group(1));
            long duration = Long.parseLong(lm.group(2));
            StringBuilder text = new StringBuilder(), timed = new StringBuilder();
            Matcher wm = word.matcher(lm.group(3));
            while (wm.find()) {
                text.append(wm.group(3));
                timed.append('(')
                        .append(Math.addExact(start, Long.parseLong(wm.group(1))))
                        .append(',')
                        .append(Long.parseLong(wm.group(2)))
                        .append(",0)")
                        .append(wm.group(3));
            }
            if (text.length() == 0) continue;
            if (lrc.length() > 0) {
                lrc.append('\n');
                yrc.append('\n');
            }
            lrc.append(
                            String.format(
                                    Locale.ROOT,
                                    "[%02d:%02d.%03d]",
                                    start / 60000,
                                    start / 1000 % 60,
                                    start % 1000))
                    .append(text);
            yrc.append('[').append(start).append(',').append(duration).append(']').append(timed);
        }
        return new LyricInfo(
                lrc.length() == 0 ? null : lrc.toString(),
                yrc.length() == 0 ? null : yrc.toString());
    }

    static SongSearchItem track(JsonNode node) {
        JsonNode b = node.path("base_info"), related = node.path("related_info");
        if (b.path("id").asText("").isEmpty()) return null;
        SongSearchItem s = new SongSearchItem();
        s.setSource(MusicSource.QISHUI);
        s.setId(b.path("id").asText());
        s.setName(b.path("name").asText());
        List<String> names = new ArrayList<>(), ids = new ArrayList<>();
        for (JsonNode a : related.path("artist_links")) {
            names.add(a.path("name").asText());
            ids.add(a.path("id").asText());
        }
        s.setArtist(String.join(" / ", names));
        s.setArtistIds(ids);
        s.setArtistId(ids.isEmpty() ? null : ids.get(0));
        s.setAlbum(related.path("album_link").path("name").asText(null));
        s.setAlbumId(related.path("album_link").path("id").asText(null));
        s.setCoverUrl(imageUrl(node.path("display_info").path("cover_url")));
        long duration = b.path("duration_ms").asLong(0);
        s.setDurationMs(duration);
        s.setDurationSec((int) (duration / 1000));
        // Catalog quality flags describe entitlements, not a verified playable quality.
        s.setAvailableQualities(Collections.emptyList());
        return s;
    }

    static SearchCollectionItem playlist(JsonNode node) {
        JsonNode b = node.path("base_info");
        SearchCollectionItem p = new SearchCollectionItem();
        p.setSource(MusicSource.QISHUI);
        p.setType(MusicSearchType.PLAYLIST);
        p.setId(
                b.path("id").asText()
                        + ":"
                        + b.path("owner_id").asText()
                        + ":"
                        + b.path("type").asInt(2));
        p.setName(b.path("title").asText());
        p.setCoverUrl(imageUrl(node.path("display_info").path("url_cover")));
        p.setTrackCount(node.path("resource_cnt").path("track_cnt").asInt(0));
        p.setCreatorName(node.path("relation_info").path("owner").path("nickname").asText(null));
        p.setPlayCount(node.path("stats").path("count_played").asLong(0));
        return p;
    }

    public PlaylistDetailView fetchPlaylistDetail(String id, int page, int size) {
        if (id != null && id.startsWith("share:")) return shares.fetch(id, page, size);
        String[] parts = id.split(":", -1);
        if (parts.length != 3
                || parts[0].trim().isEmpty()
                || parts[1].trim().isEmpty()
                || !parts[2].matches("\\d{1,10}"))
            throw new BusinessException(400, "Use the full Qishui playlist ID returned by search");
        int type;
        try {
            type = Integer.parseInt(parts[2]);
        } catch (NumberFormatException ex) {
            throw new BusinessException(400, "Invalid Qishui playlist type");
        }
        if (page < 1 || page > 1000 || size < 1 || size > 100)
            throw new BusinessException(400, "Invalid Qishui pagination");
        ObjectNode b =
                mapper.createObjectNode()
                        .put("playlist_id", parts[0])
                        .put("owner_id", parts[1])
                        .put("type", type)
                        .put("count", size)
                        .put("cursor", Long.toString((long) (page - 1) * size));
        JsonNode data = post("/playlist/detail/", b), pl = data.path("playlist");
        if (pl.path("base_info").path("id").asText("").isEmpty()
                || !data.path("media_entities").isArray()) throw QishuiHttpClient.failure();
        SearchCollectionItem summary = playlist(pl);
        List<SongSearchItem> tracks = new ArrayList<>();
        for (JsonNode e : data.path("media_entities")) {
            SongSearchItem s = track(e.path("track"));
            if (s == null) s = track(e.path("track_entity"));
            if (s != null) tracks.add(s);
        }
        return new PlaylistDetailView(
                id,
                MusicSource.QISHUI,
                summary.getName(),
                summary.getCoverUrl(),
                pl.path("base_info").path("desc").asText(null),
                summary.getCreatorName(),
                summary.getPlayCount(),
                null,
                page,
                size,
                summary.getTrackCount(),
                tracks);
    }

    private JsonNode artistTracks(String id, int page, int size) {
        if (page < 1 || page > 1000 || size < 1 || size > 100)
            throw new BusinessException(400, "Invalid Qishui pagination");
        JsonNode data =
                post(
                        "/artist/tracks/",
                        mapper.createObjectNode()
                                .put("artist_id", id)
                                .put("count", size)
                                .put("cursor", Long.toString((long) (page - 1) * size)));
        if (!data.path("tracks").isArray()) throw QishuiHttpClient.failure();
        return data;
    }

    public ArtistWorksView fetchArtistWorks(String id, String type, int page, int size) {
        if ("album".equals(type))
            return new ArtistWorksView(id, MusicSource.QISHUI, "album", page, size, null,
                    Collections.emptyList(), fetchArtistAlbums(id, page, size));
        if (!"song".equals(type)) throw unsupported("unsupported artist works type");
        List<SongSearchItem> tracks = new ArrayList<>();
        for (JsonNode t : artistTracks(id, page, size).path("tracks")) {
            SongSearchItem song = track(t);
            if (song != null) tracks.add(song);
        }
        return new ArtistWorksView(
                id, MusicSource.QISHUI, "song", page, size, null, tracks, Collections.emptyList());
    }

    public ArtistDetailView fetchArtistDetail(String id) {
        for (JsonNode t : artistTracks(id, 1, 1).path("tracks"))
            for (JsonNode artist : t.path("related_info").path("artist_links")) {
                if (!id.equals(artist.path("id").asText())) continue;
                ArtistDetailView view = new ArtistDetailView();
                view.setId(id);
                view.setSource(MusicSource.QISHUI);
                view.setName(artist.path("name").asText());
                view.setCoverUrl(imageUrl(artist.path("url_avatar")));
                return view;
            }
        return null;
    }


    /** Upstream /search/ has no "albums" group; aggregate distinct albums from real track hits. */
    private List<SearchCollectionItem> searchAlbums(String keyword, int page, int size) {
        int fetch = Math.min(100, Math.max(size * (page + 1), 40));
        Map<String, SearchCollectionItem> unique = new LinkedHashMap<>();
        for (JsonNode e : searchEntities(keyword, "track", 1, fetch)) {
            JsonNode t = e.path("track");
            JsonNode link = t.path("related_info").path("album_link");
            String id = link.path("id").asText("");
            if (id.isEmpty() || unique.containsKey(id)) continue;
            SearchCollectionItem a = new SearchCollectionItem();
            a.setSource(MusicSource.QISHUI);
            a.setType(MusicSearchType.ALBUM);
            a.setId(id);
            a.setName(link.path("name").asText(""));
            a.setCoverUrl(imageUrl(t.path("display_info").path("cover_url")));
            JsonNode artists = t.path("related_info").path("artist_links");
            if (artists.isArray() && artists.size() > 0) {
                a.setArtist(artists.get(0).path("name").asText(""));
                a.setArtistId(artists.get(0).path("id").asText(""));
                List<String> ids = new ArrayList<>();
                for (JsonNode ar : artists) ids.add(ar.path("id").asText());
                a.setArtistIds(ids);
            }
            unique.put(id, a);
        }
        List<SearchCollectionItem> all = new ArrayList<>(unique.values());
        int from = Math.min(all.size(), (page - 1) * size);
        int to = Math.min(all.size(), from + size);
        return new ArrayList<>(all.subList(from, to));
    }

    static SearchCollectionItem artist(JsonNode node) {
        JsonNode b = node.path("base_info");
        if (b.path("id").asText("").isEmpty()) return null;
        SearchCollectionItem a = new SearchCollectionItem();
        a.setSource(MusicSource.QISHUI);
        a.setType(MusicSearchType.ARTIST);
        a.setId(b.path("id").asText());
        a.setName(b.path("name").asText());
        a.setCoverUrl(imageUrl(b.path("url_avatar")));
        a.setTrackCount(b.path("count_tracks").asInt(0));
        return a;
    }

    public AlbumDetailView fetchAlbumDetail(String id, int page, int size) {
        if (page < 1 || page > 1000 || size < 1 || size > 100)
            throw new BusinessException(400, "Invalid Qishui pagination");
        JsonNode data = post("/album/detail/", mapper.createObjectNode()
                .put("album_id", id)
                .put("count", size)
                .put("cursor", Long.toString((long) (page - 1) * size)));
        JsonNode info = data.path("album_info");
        if (info.path("base_info").path("id").asText("").isEmpty()
                || !data.path("tracks").isArray()) throw QishuiHttpClient.failure();
        JsonNode b = info.path("base_info");
        List<SongSearchItem> songs = new ArrayList<>();
        for (JsonNode t : data.path("tracks")) {
            SongSearchItem s = track(t);
            if (s != null) songs.add(s);
        }
        String artist = null;
        JsonNode artists = info.path("related_info").path("artists");
        if (artists.isArray() && artists.size() > 0)
            artist = artists.get(0).path("name").asText(null);
        AlbumDetailView view = new AlbumDetailView();
        view.setId(id);
        view.setSource(MusicSource.QISHUI);
        view.setName(b.path("name").asText());
        view.setCoverUrl(imageUrl(info.path("display").path("url_cover")));
        view.setDescription(b.path("intro").asText(null));
        view.setArtist(artist);
        view.setPublishTime(publishTime(b.path("release_date").asText("")));
        view.setPage(page);
        view.setPageSize(size);
        view.setTotal(b.path("count_tracks").asInt(0));
        view.setList(songs);
        return view;
    }

    private List<AlbumSummaryItem> fetchArtistAlbums(String id, int page, int size) {
        if (page < 1 || page > 1000 || size < 1 || size > 100)
            throw new BusinessException(400, "Invalid Qishui pagination");
        JsonNode data = post("/artist/albums/", mapper.createObjectNode()
                .put("artist_id", id)
                .put("count", size)
                .put("cursor", Long.toString((long) (page - 1) * size)));
        if (!data.path("albums").isArray()) throw QishuiHttpClient.failure();
        List<AlbumSummaryItem> albums = new ArrayList<>();
        for (JsonNode a : data.path("albums")) {
            JsonNode b = a.path("base_info");
            String albumId = b.path("id").asText("");
            if (albumId.isEmpty()) continue;
            AlbumSummaryItem item = new AlbumSummaryItem();
            item.setId(albumId);
            item.setSource(MusicSource.QISHUI);
            item.setName(b.path("name").asText());
            item.setCoverUrl(imageUrl(a.path("display").path("url_cover")));
            item.setPublishTime(publishTime(b.path("release_date").asText("")));
            item.setTrackCount(b.path("count_tracks").asInt(0));
            item.setDescription(b.path("intro").asText(null));
            List<String> names = new ArrayList<>(), ids = new ArrayList<>();
            for (JsonNode ar : a.path("related_info").path("artists")) {
                names.add(ar.path("name").asText());
                ids.add(ar.path("id").asText());
            }
            item.setArtist(names.isEmpty() ? null : String.join(" / ", names));
            item.setArtistIds(ids);
            item.setArtistId(ids.isEmpty() ? null : ids.get(0));
            albums.add(item);
        }
        return albums;
    }

    private static String publishTime(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            long sec = Long.parseLong(raw);
            if (sec <= 0) return null;
            // Upstream release_date is stored as Beijing midnight epoch seconds; UTC would
            // shift every release to the previous day for Chinese users.
            return Instant.ofEpochSecond(sec).atZone(ZoneOffset.ofHours(8)).toLocalDate().toString();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * No official chart protocol has been established for this adapter. These compatibility
     * views use search results. The independent /feed/song-tab/ endpoint supplies recommendations.
     */
    static final List<String> DISCOVERY_KEYWORDS =
            Collections.unmodifiableList(Arrays.asList("热门", "华语流行", "新歌"));
    private static final Set<String> DISCOVERY_IDS = new HashSet<>(DISCOVERY_KEYWORDS);
    private static final String DISCOVERY_NOTE = "基于汽水音乐真实搜索，非官方榜单";

    public List<ToplistItem> discoveryToplists() {
        List<ToplistItem> list = new ArrayList<>();
        for (String kw : DISCOVERY_KEYWORDS)
            list.add(new ToplistItem(kw, MusicSource.QISHUI, "搜索发现 · " + kw,
                    null, DISCOVERY_NOTE, null));
        return list;
    }

    public ToplistDetailView discoveryToplistDetail(String keyword, int page, int size) {
        if (!DISCOVERY_IDS.contains(keyword))
            throw new BusinessException(400, "Unknown Qishui discovery id");
        int s = Math.min(30, Math.max(1, size));
        List<SongSearchItem> songs = search(keyword, page, s);
        return new ToplistDetailView(keyword, MusicSource.QISHUI, "搜索发现 · " + keyword,
                null, DISCOVERY_NOTE, null, page, s, null, songs);
    }

    public List<PlaylistItem> discoverPlaylists(String keyword, int limit) {
        return discoverPlaylists(keyword, 1, limit);
    }

    public List<PlaylistItem> discoverPlaylists(String keyword, int page, int limit) {
        int size = Math.min(50, Math.max(1, limit));
        return searchEntities(keyword, "playlist", page, size).stream()
                .map(e -> playlistItem(playlist(e.path("playlist"))))
                .collect(Collectors.toList());
    }

    public List<PlaylistItem> discoveryPlaylists(int limit) {
        int size = Math.min(50, Math.max(1, limit));
        Map<String, PlaylistItem> unique = new LinkedHashMap<>();
        for (String kw : DISCOVERY_KEYWORDS) {
            if (unique.size() >= size) break;
            for (PlaylistItem p : discoverPlaylists(kw, Math.min(20, size))) {
                if (unique.size() >= size) break;
                unique.putIfAbsent(p.getId(), p);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static PlaylistItem playlistItem(SearchCollectionItem s) {
        return new PlaylistItem(s.getId(), MusicSource.QISHUI, s.getName(), s.getCoverUrl(),
                null, s.getCreatorName(), s.getTrackCount(), s.getPlayCount());
    }

    static String imageUrl(JsonNode node) {
        if (node.isTextual()) return node.asText();
        String url = node.path("urls").path(0).asText(null);
        if (url == null) return null;
        String uri = node.path("uri").asText("");
        if (!uri.isEmpty() && url.endsWith("/")) {
            String template = node.path("template_prefix").asText("");
            return url + uri + (template.isEmpty() ? "" : "~" + template + "-image.image");
        }
        return url;
    }

    static MusicBusinessException unsupported(String detail) {
        return new MusicBusinessException(
                MusicErrorCode.UNSUPPORTED_OPERATION, "Qishui: " + detail);
    }
}
