package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.OkHttpConfig;
import com.example.website.dto.music.MusicSearchType;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.PlaylistDetailView;
import com.example.website.dto.music.PlaylistItem;
import com.example.website.dto.music.PlaylistListView;
import com.example.website.dto.music.SearchCollectionItem;
import com.example.website.dto.music.SongSearchItem;
import com.example.website.dto.music.ToplistDetailView;
import com.example.website.dto.music.ToplistItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NeteaseMusicClient {

    private static final String CLOUD_SEARCH_URL = "https://music.163.com/api/cloudsearch/pc";
    private static final String SEARCH_URL = "https://music.163.com/api/search/get/web";
    private static final String LYRIC_URL  = "https://music.163.com/api/song/lyric/v1";
    private static final String TOPLIST_URL = "https://music.163.com/api/toplist";
    private static final String PLAYLIST_LIST_URL = "https://music.163.com/api/playlist/list";
    private static final String PLAYLIST_DETAIL_URL = "https://music.163.com/api/v3/playlist/detail";
    private static final String REFERER    = "https://music.163.com";
    private static final String COOKIE     = "os=pc";
    private static final String USER_AGENT = "Mozilla/5.0";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    @Qualifier(OkHttpConfig.CLIENT_MUSIC)
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public List<SongSearchItem> search(String keyword, int page, int pageSize) {
        Map<String, Object> json = searchJson(keyword, MusicSearchType.SONG, page, pageSize);
        return parseSearch(json);
    }

    public CollectionSearchResult searchCollections(String keyword, MusicSearchType type, int page, int pageSize) {
        if (type == null || type == MusicSearchType.SONG) {
            return new CollectionSearchResult(0L, Collections.emptyList());
        }
        Map<String, Object> json = searchJson(keyword, type, page, pageSize);
        return parseCollectionSearch(json, type);
    }

    private Map<String, Object> searchJson(String keyword, MusicSearchType type, int page, int pageSize) {
        int offset = Math.max(0, (page - 1) * pageSize);
        String searchType = neteaseSearchType(type);

        try {
            return callJson(searchRequest(CLOUD_SEARCH_URL, keyword, searchType, offset, pageSize),
                    MusicErrorCode.UPSTREAM_SEARCH_FAILED);
        } catch (MusicBusinessException e) {
            log.warn("Netease cloudsearch failed, falling back to legacy search: {}", e.getMessage());
            return callJson(searchRequest(SEARCH_URL, keyword, searchType, offset, pageSize),
                    MusicErrorCode.UPSTREAM_SEARCH_FAILED);
        }
    }

    private Request searchRequest(String baseUrl, String keyword, String type, int offset, int pageSize) {
        HttpUrl url = HttpUrl.parse(baseUrl).newBuilder()
                .addQueryParameter("s", keyword)
                .addQueryParameter("type", type)
                .addQueryParameter("offset", String.valueOf(offset))
                .addQueryParameter("limit", String.valueOf(pageSize))
                .build();
        return new Request.Builder()
                .url(url)
                .header("Referer", REFERER + "/")
                .header("Cookie", COOKIE)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
    }

    /**
     * Returns [lineLyrics, karaokeLyrics]. Either element may be null.
     */
    public String[] fetchLyric(String songId) {
        HttpUrl url = HttpUrl.parse(LYRIC_URL).newBuilder()
                .addQueryParameter("cp", "false")
                .addQueryParameter("id", songId)
                .addQueryParameter("lv", "0")
                .addQueryParameter("tv", "0")
                .addQueryParameter("rv", "0")
                .addQueryParameter("kv", "0")
                .addQueryParameter("yv", "0")
                .addQueryParameter("ytv", "0")
                .addQueryParameter("yrv", "0")
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("Cookie", COOKIE)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();

        Map<String, Object> json = callJson(req, MusicErrorCode.UPSTREAM_LYRIC_FAILED);
        String line = readLyric(json, "lrc");
        String karaoke = readLyric(json, "yrc");
        if (karaoke == null) karaoke = readLyric(json, "klyric");
        return new String[]{line, karaoke};
    }

    @SuppressWarnings("unchecked")
    private String readLyric(Map<String, Object> json, String key) {
        Object obj = json.get(key);
        if (!(obj instanceof Map)) return null;
        Object v = ((Map<String, Object>) obj).get("lyric");
        if (!(v instanceof String)) return null;
        String s = (String) v;
        return s.isEmpty() ? null : s;
    }

    /**
     * Fetch song metadata (name/artist/album) by id. Used by the play
     * fallback path in {@link MusicService} when the primary source's
     * tf-pay call fails and we need a keyword to search other sources.
     * Returns null if upstream errors or the song is unknown.
     */
    @SuppressWarnings("unchecked")
    public SongSearchItem fetchSongInfo(String id) {
        HttpUrl url = HttpUrl.parse("https://music.163.com/api/song/detail").newBuilder()
                .addQueryParameter("ids", "[" + id + "]")
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("Cookie", COOKIE)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        Map<String, Object> json;
        try {
            json = callJson(req, MusicErrorCode.UPSTREAM_PLAY_FAILED);
        } catch (Exception e) {
            log.debug("Netease songInfo lookup failed for id={}: {}", id, e.getMessage());
            return null;
        }
        Object songs = json.get("songs");
        if (!(songs instanceof List) || ((List<?>) songs).isEmpty()) return null;
        Object first = ((List<?>) songs).get(0);
        if (!(first instanceof Map)) return null;
        Map<String, Object> s = (Map<String, Object>) first;

        SongSearchItem v = new SongSearchItem();
        v.setSource(MusicSource.NETEASE);
        v.setId(asString(s.get("id")));
        v.setName(asString(s.get("name")));
        // Old /api/song/detail returns "artists": [{name: ...}]. v3 uses "ar".
        Object arr = s.get("artists");
        if (!(arr instanceof List)) arr = s.get("ar");
        v.setArtist(joinArtists(arr));
        Map<String, Object> al = asMap(s.get("album"));
        if (al == null) al = asMap(s.get("al"));
        if (al != null) {
            v.setAlbum(asString(al.get("name")));
        }
        return (v.getName() == null || v.getName().isEmpty()) ? null : v;
    }

    /**
     * Netease toplist list ({@code api/toplist}). Returns the curated set of
     * charts exposed on music.163.com — each entry's {@code id} can be fed
     * back into {@link #fetchToplistDetail} to pull the songs.
     */
    public List<ToplistItem> fetchToplists() {
        Request req = new Request.Builder()
                .url(TOPLIST_URL)
                .header("Referer", REFERER)
                .header("Cookie", COOKIE)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        Map<String, Object> json = callJson(req, MusicErrorCode.UPSTREAM_TOPLIST_FAILED);
        return parseToplists(json);
    }

    public PlaylistListView fetchPlaylists(String category, String order, int page, int pageSize) {
        int offset = Math.max(0, (page - 1) * pageSize);
        HttpUrl url = HttpUrl.parse(PLAYLIST_LIST_URL).newBuilder()
                .addQueryParameter("cat", category)
                .addQueryParameter("order", order)
                .addQueryParameter("limit", String.valueOf(pageSize))
                .addQueryParameter("offset", String.valueOf(offset))
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("Cookie", COOKIE)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        Map<String, Object> json = callJson(req, MusicErrorCode.UPSTREAM_PLAYLIST_FAILED);
        return parsePlaylistList(json, category, order, page, pageSize);
    }

    public PlaylistDetailView fetchPlaylistDetail(String id, int page, int pageSize) {
        HttpUrl url = HttpUrl.parse(PLAYLIST_DETAIL_URL).newBuilder()
                .addQueryParameter("id", id)
                .addQueryParameter("n", "1000")
                .addQueryParameter("s", "0")
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("Cookie", COOKIE)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        Map<String, Object> json = callJson(req, MusicErrorCode.UPSTREAM_PLAYLIST_FAILED);
        return parsePlaylistDetail(json, id, page, pageSize);
    }

    /**
     * Netease playlist/toplist detail ({@code api/v3/playlist/detail}). Same
     * endpoint serves both regular playlists and official charts. Upstream
     * returns all tracks in one shot (no native offset/limit), so we ask for
     * up to 1000 and slice in memory.
     */
    public ToplistDetailView fetchToplistDetail(String id, int page, int pageSize) {
        HttpUrl url = HttpUrl.parse(PLAYLIST_DETAIL_URL).newBuilder()
                .addQueryParameter("id", id)
                .addQueryParameter("n", "1000")
                .addQueryParameter("s", "0")
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("Cookie", COOKIE)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        Map<String, Object> json = callJson(req, MusicErrorCode.UPSTREAM_TOPLIST_FAILED);
        return parseToplistDetail(json, id, page, pageSize);
    }

    @SuppressWarnings("unchecked")
    private List<ToplistItem> parseToplists(Map<String, Object> json) {
        Object listObj = json.get("list");
        if (!(listObj instanceof List)) return Collections.emptyList();
        List<ToplistItem> out = new ArrayList<>();
        for (Object item : (List<?>) listObj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> s = (Map<String, Object>) item;
            ToplistItem t = new ToplistItem();
            t.setSource(MusicSource.NETEASE);
            t.setId(asString(s.get("id")));
            t.setName(asString(s.get("name")));
            t.setCoverUrl(asString(s.get("coverImgUrl")));
            t.setDescription(asString(s.get("description")));
            t.setUpdateTime(asString(s.get("updateFrequency")));
            if (t.getId() != null && !t.getId().isEmpty()) out.add(t);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private PlaylistListView parsePlaylistList(Map<String, Object> json,
                                               String category,
                                               String order,
                                               int page,
                                               int pageSize) {
        PlaylistListView v = new PlaylistListView();
        v.setSource(MusicSource.NETEASE);
        v.setCategory(category);
        v.setOrder(order);
        v.setPage(page);
        v.setPageSize(pageSize);
        v.setList(Collections.emptyList());

        Long total = asLongObj(json.get("total"));
        if (total != null) v.setTotal(total);

        Object listObj = json.get("playlists");
        if (!(listObj instanceof List)) return v;

        List<PlaylistItem> out = new ArrayList<>();
        for (Object item : (List<?>) listObj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> s = (Map<String, Object>) item;
            PlaylistItem p = new PlaylistItem();
            p.setSource(MusicSource.NETEASE);
            p.setId(asString(s.get("id")));
            p.setName(asString(s.get("name")));
            p.setCoverUrl(asString(s.get("coverImgUrl")));
            p.setDescription(asString(s.get("description")));
            p.setTrackCount(asIntObj(s.get("trackCount")));
            p.setPlayCount(asLongObj(s.get("playCount")));
            Map<String, Object> creator = asMap(s.get("creator"));
            if (creator != null) {
                p.setCreatorName(asString(creator.get("nickname")));
            }
            if (p.getId() != null && !p.getId().isEmpty()) out.add(p);
        }
        v.setList(out);
        return v;
    }

    @SuppressWarnings("unchecked")
    private ToplistDetailView parseToplistDetail(Map<String, Object> json, String reqId, int page, int pageSize) {
        ToplistDetailView v = new ToplistDetailView();
        v.setId(reqId);
        v.setSource(MusicSource.NETEASE);
        v.setPage(page);
        v.setPageSize(pageSize);
        v.setList(Collections.emptyList());

        Object pl = json.get("playlist");
        if (!(pl instanceof Map)) return v;
        Map<String, Object> playlist = (Map<String, Object>) pl;

        String plId = asString(playlist.get("id"));
        if (plId != null && !plId.isEmpty()) v.setId(plId);
        v.setName(asString(playlist.get("name")));
        v.setCoverUrl(asString(playlist.get("coverImgUrl")));
        v.setDescription(asString(playlist.get("description")));
        Object ut = playlist.get("updateTime");
        if (ut != null) v.setUpdateTime(asString(ut));

        Integer trackCount = asIntObj(playlist.get("trackCount"));
        Object tracksObj = playlist.get("tracks");
        if (!(tracksObj instanceof List)) {
            v.setTotal(trackCount);
            return v;
        }
        List<?> tracks = (List<?>) tracksObj;
        v.setTotal(trackCount != null ? trackCount : tracks.size());

        int from = Math.max(0, (page - 1) * pageSize);
        if (from >= tracks.size()) return v;
        int to = Math.min(tracks.size(), from + pageSize);

        List<SongSearchItem> out = new ArrayList<>();
        for (Object track : tracks.subList(from, to)) {
            if (!(track instanceof Map)) continue;
            SongSearchItem si = toSongItem((Map<String, Object>) track);
            if (si.getId() != null && !si.getId().isEmpty()) out.add(si);
        }
        v.setList(out);
        return v;
    }

    @SuppressWarnings("unchecked")
    private PlaylistDetailView parsePlaylistDetail(Map<String, Object> json, String reqId, int page, int pageSize) {
        PlaylistDetailView v = new PlaylistDetailView();
        v.setId(reqId);
        v.setSource(MusicSource.NETEASE);
        v.setPage(page);
        v.setPageSize(pageSize);
        v.setList(Collections.emptyList());

        Object pl = json.get("playlist");
        if (!(pl instanceof Map)) return v;
        Map<String, Object> playlist = (Map<String, Object>) pl;

        String plId = asString(playlist.get("id"));
        if (plId != null && !plId.isEmpty()) v.setId(plId);
        v.setName(asString(playlist.get("name")));
        v.setCoverUrl(asString(playlist.get("coverImgUrl")));
        v.setDescription(asString(playlist.get("description")));
        v.setPlayCount(asLongObj(playlist.get("playCount")));
        Object ut = playlist.get("updateTime");
        if (ut != null) v.setUpdateTime(asString(ut));

        Map<String, Object> creator = asMap(playlist.get("creator"));
        if (creator != null) {
            v.setCreatorName(asString(creator.get("nickname")));
        }

        Integer trackCount = asIntObj(playlist.get("trackCount"));
        Object tracksObj = playlist.get("tracks");
        if (!(tracksObj instanceof List)) {
            v.setTotal(trackCount);
            return v;
        }
        List<?> tracks = (List<?>) tracksObj;
        v.setTotal(trackCount != null ? trackCount : tracks.size());

        int from = Math.max(0, (page - 1) * pageSize);
        if (from >= tracks.size()) return v;
        int to = Math.min(tracks.size(), from + pageSize);

        List<SongSearchItem> out = new ArrayList<>();
        for (Object track : tracks.subList(from, to)) {
            if (!(track instanceof Map)) continue;
            SongSearchItem si = toSongItem((Map<String, Object>) track);
            if (si.getId() != null && !si.getId().isEmpty()) out.add(si);
        }
        v.setList(out);
        return v;
    }

    @SuppressWarnings("unchecked")
    private List<SongSearchItem> parseSearch(Map<String, Object> json) {
        Object result = json.get("result");
        if (!(result instanceof Map)) return Collections.emptyList();
        Object songsObj = ((Map<?, ?>) result).get("songs");
        if (!(songsObj instanceof List)) return Collections.emptyList();

        List<SongSearchItem> out = new ArrayList<>();
        for (Object item : (List<?>) songsObj) {
            if (!(item instanceof Map)) continue;
            SongSearchItem v = toSongItem((Map<String, Object>) item);
            if (v.getId() != null && !v.getId().isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private CollectionSearchResult parseCollectionSearch(Map<String, Object> json, MusicSearchType type) {
        Object resultObj = json.get("result");
        if (!(resultObj instanceof Map)) {
            return new CollectionSearchResult(0L, Collections.emptyList());
        }
        Map<String, Object> result = (Map<String, Object>) resultObj;
        Object listObj;
        Long total;
        switch (type) {
            case ARTIST:
                listObj = result.get("artists");
                total = asLongObj(result.get("artistCount"));
                break;
            case ALBUM:
                listObj = result.get("albums");
                total = asLongObj(result.get("albumCount"));
                break;
            case PLAYLIST:
                listObj = result.get("playlists");
                total = asLongObj(result.get("playlistCount"));
                break;
            default:
                listObj = Collections.emptyList();
                total = 0L;
                break;
        }
        if (!(listObj instanceof List)) {
            return new CollectionSearchResult(total == null ? 0L : total, Collections.emptyList());
        }

        List<SearchCollectionItem> out = new ArrayList<>();
        for (Object item : (List<?>) listObj) {
            if (!(item instanceof Map)) continue;
            SearchCollectionItem mapped = toCollectionItem((Map<String, Object>) item, type);
            if (mapped.getId() != null && !mapped.getId().isEmpty()) {
                out.add(mapped);
            }
        }
        return new CollectionSearchResult(total == null ? (long) out.size() : total, out);
    }

    private SearchCollectionItem toCollectionItem(Map<String, Object> s, MusicSearchType type) {
        SearchCollectionItem item = new SearchCollectionItem();
        item.setSource(MusicSource.NETEASE);
        item.setType(type);
        item.setId(asString(s.get("id")));
        item.setName(asString(s.get("name")));
        switch (type) {
            case ARTIST:
                item.setCoverUrl(firstNonEmpty(asString(s.get("picUrl")), asString(s.get("img1v1Url"))));
                item.setTrackCount(asIntObj(s.get("musicSize")));
                break;
            case ALBUM:
                item.setCoverUrl(asString(s.get("picUrl")));
                item.setTrackCount(asIntObj(s.get("size")));
                item.setArtist(joinArtists(firstValue(s.get("artists"), singletonArtist(s.get("artist")))));
                break;
            case PLAYLIST:
                item.setCoverUrl(asString(s.get("coverImgUrl")));
                item.setTrackCount(asIntObj(s.get("trackCount")));
                item.setPlayCount(asLongObj(s.get("playCount")));
                Map<String, Object> creator = asMap(s.get("creator"));
                if (creator != null) {
                    item.setCreatorName(asString(creator.get("nickname")));
                }
                break;
            default:
                break;
        }
        return item;
    }

    private static String neteaseSearchType(MusicSearchType type) {
        if (type == null) return "1";
        switch (type) {
            case ALBUM:
                return "10";
            case ARTIST:
                return "100";
            case PLAYLIST:
                return "1000";
            case SONG:
            default:
                return "1";
        }
    }

    private SongSearchItem toSongItem(Map<String, Object> s) {
        SongSearchItem v = new SongSearchItem();
        v.setSource(MusicSource.NETEASE);
        v.setId(asString(s.get("id")));
        v.setName(asString(s.get("name")));
        Object artists = s.get("ar");
        if (!(artists instanceof List)) artists = s.get("artists");
        v.setArtist(joinArtists(artists));
        Map<String, Object> al = asMap(s.get("al"));
        if (al == null) al = asMap(s.get("album"));
        if (al != null) {
            v.setAlbum(asString(al.get("name")));
            v.setAlbumId(asString(al.get("id")));
            v.setCoverUrl(firstNonEmpty(asString(al.get("picUrl")), neteaseImageUrl(al.get("picId"))));
        }
        Long dt = asLongObj(s.get("dt"));
        if (dt != null) {
            v.setDurationMs(dt);
            v.setDurationSec((int) (dt / 1000L));
        }
        v.setAvailableQualities(qualitiesFromSong(s));
        return v;
    }

    private List<String> qualitiesFromSong(Map<String, Object> s) {
        List<String> q = new ArrayList<>();
        if (s.get("l")  != null) q.add("128k");
        if (s.get("h")  != null) q.add("320k");
        if (s.get("sq") != null) q.add("flac");
        if (s.get("hr") != null) q.add("flac24bit");
        return q;
    }

    @SuppressWarnings("unchecked")
    private String joinArtists(Object arObj) {
        if (!(arObj instanceof List)) return null;
        StringBuilder sb = new StringBuilder();
        for (Object a : (List<?>) arObj) {
            if (!(a instanceof Map)) continue;
            String n = asString(((Map<String, Object>) a).get("name"));
            if (n == null || n.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(n);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private Map<String, Object> callJson(Request req, MusicErrorCode onFail) {
        try (Response resp = okHttpClient.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            String raw = rb == null ? "" : rb.string();
            if (!resp.isSuccessful()) {
                log.warn("Netease upstream non-2xx status={} body={}", resp.code(), truncate(raw));
                throw new MusicBusinessException(onFail,
                        "netease upstream returned " + resp.code());
            }
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (IOException e) {
            log.warn("Netease upstream I/O failed: {}", e.getMessage());
            throw new MusicBusinessException(onFail, "netease upstream I/O failed: " + e.getMessage(), e);
        } catch (MusicBusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Netease upstream parse failed: {}", e.getMessage());
            throw new MusicBusinessException(onFail, "netease upstream parse failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long asLongObj(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static Integer asIntObj(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object singletonArtist(Object artist) {
        if (artist instanceof Map) {
            List<Map<String, Object>> list = new ArrayList<>();
            list.add((Map<String, Object>) artist);
            return list;
        }
        return null;
    }

    private static Object firstValue(Object... values) {
        for (Object value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static String firstNonEmpty(String... vs) {
        for (String v : vs) if (v != null && !v.isEmpty()) return v;
        return null;
    }

    static String neteaseImageUrl(Object picId) {
        String id = asString(picId);
        if (id == null || id.isEmpty() || "0".equals(id)) return null;
        return "https://music.163.com/api/img/blur/" + id + "?param=130y130";
    }

    public static class CollectionSearchResult {
        private final Long total;
        private final List<SearchCollectionItem> list;

        public CollectionSearchResult(Long total, List<SearchCollectionItem> list) {
            this.total = total;
            this.list = list;
        }

        public Long getTotal() {
            return total;
        }

        public List<SearchCollectionItem> getList() {
            return list;
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
