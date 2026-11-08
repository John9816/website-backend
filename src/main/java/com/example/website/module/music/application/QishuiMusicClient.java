package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.ArtistDetailView;
import com.example.website.module.music.api.dto.ArtistWorksView;
import com.example.website.module.music.api.dto.LyricInfo;
import com.example.website.module.music.api.dto.MusicSearchType;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.PlaylistDetailView;
import com.example.website.module.music.api.dto.SearchCollectionItem;
import com.example.website.module.music.api.dto.SongSearchItem;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.*;
import java.util.stream.Collectors;

@Component
public class QishuiMusicClient {
    private final QishuiHttpClient http;
    private final ObjectMapper mapper;
    private final String deviceId = UUID.randomUUID().toString().replace("-", "");
    private final ReentrantLock tokenLock = new ReentrantLock();
    private final AtomicReference<CatalogToken> clientToken = new AtomicReference<>();

    private static final class CatalogToken {
        final String value;
        final long expiresAt;
        CatalogToken(String value, long expiresAt) { this.value = value; this.expiresAt = expiresAt; }
        boolean valid() { return System.currentTimeMillis() < expiresAt; }
    }

    public QishuiMusicClient(QishuiHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
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
        body.set("common_params", mapper.createObjectNode());
        CatalogToken used = token();
        JsonNode r =
                http.post(
                                QishuiHttpClient.PLATFORM + path,
                                QishuiHttpClient.common(deviceId),
                                body,
                                Collections.singletonMap("access-token", used.value))
                        .json;
        long code = r.path("err_no").asLong(0);
        // A late failure may invalidate only the exact generation used by that request.
        if (code == 28001003 || code == 28001008) clientToken.compareAndSet(used, null);
        return QishuiHttpClient.checked(r);
    }

    public List<SongSearchItem> search(String keyword, int page, int pageSize) {
        return searchEntities(keyword, "track", page, pageSize).stream()
                .map(e -> track(e.path("track")))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public NeteaseMusicClient.CollectionSearchResult searchCollections(
            String keyword, MusicSearchType type, int page, int pageSize) {
        if (type != MusicSearchType.PLAYLIST)
            throw unsupported("only song and playlist search are available");
        List<SearchCollectionItem> list =
                searchEntities(keyword, "playlist", page, pageSize).stream()
                        .map(e -> playlist(e.path("playlist")))
                        .collect(Collectors.toList());
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
        JsonNode entity = media(id).path("display_info").path("lyric_info").path("lyric_entity");
        String content = entity.path("content").asText("");
        if ("krc".equals(entity.path("type").asText())) return krc(content);
        return new LyricInfo(content.isEmpty() ? null : content, null);
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
        if (!"song".equals(type)) throw unsupported("artist album listing is not available");
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
