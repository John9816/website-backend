package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.OkHttpConfig;
import com.example.website.dto.music.AlbumDetailView;
import com.example.website.dto.music.AlbumSummaryItem;
import com.example.website.dto.music.ArtistDetailView;
import com.example.website.dto.music.ArtistWorksView;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.example.website.service.music.MusicValueUtils.asInt;
import static com.example.website.service.music.MusicValueUtils.asLongObj;
import static com.example.website.service.music.MusicValueUtils.asMap;
import static com.example.website.service.music.MusicValueUtils.asString;
import static com.example.website.service.music.MusicValueUtils.firstNonEmpty;
import static com.example.website.service.music.MusicValueUtils.idsFromMaps;
import static com.example.website.service.music.MusicValueUtils.truncate;

@Slf4j
@Component
@RequiredArgsConstructor
public class NeteaseMusicClient {

    private static final String CLOUD_SEARCH_URL = "https://music.163.com/api/cloudsearch/pc";
    private static final String SEARCH_URL = "https://music.163.com/api/search/get/web";
    private static final String LYRIC_URL  = "https://music.163.com/api/song/lyric/v1";
    private static final String TOPLIST_URL = "https://music.163.com/api/toplist";
    private static final String PLAYLIST_LIST_URL = "https://music.163.com/api/playlist/list";
    private static final String RECOMMENDED_PLAYLIST_URL = "https://music.163.com/api/personalized/playlist";
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
        setArtistIds(v, arr);
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

    @SuppressWarnings("unchecked")
    public ArtistDetailView fetchArtistDetail(String id) {
        try {
            // Working endpoint: /api/v1/artist/{id} returns briefDesc.
            HttpUrl url = HttpUrl.parse("https://music.163.com/api/v1/artist/" + id).newBuilder().build();
            Request req = new Request.Builder()
                    .url(url)
                    .header("Referer", REFERER)
                    .header("Cookie", COOKIE)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build();
            Map<String, Object> json = callJson(req, MusicErrorCode.UPSTREAM_PLAY_FAILED);
            Object artistObj = json.get("artist");
            if (!(artistObj instanceof Map)) {
                return null;
            }
            Map<String, Object> artist = (Map<String, Object>) artistObj;
            ArtistDetailView v = new ArtistDetailView();
            v.setId(id);
            v.setSource(MusicSource.NETEASE);
            v.setName(asString(artist.get("name")));
            v.setCoverUrl(firstNonEmpty(asString(artist.get("picUrl")), asString(artist.get("img1v1Url"))));
            v.setSongCount(asInt(artist.get("musicSize")));
            v.setAlbumCount(asInt(artist.get("albumSize")));
            v.setDescription(asString(artist.get("briefDesc")));
            return v;
        } catch (Exception e) {
            log.warn("Netease artist detail failed for {}: {}", id, e.getMessage());
            return null;
        }
    }

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

    /** Anonymous/homepage recommended playlists. This is separate from the category playlist feed. */
    public PlaylistListView fetchRecommendedPlaylists(int limit) {
        HttpUrl url = HttpUrl.parse(RECOMMENDED_PLAYLIST_URL).newBuilder()
                .addQueryParameter("limit", String.valueOf(limit))
                .build();
        Map<String, Object> json = callJson(officialGet(url), MusicErrorCode.UPSTREAM_PLAYLIST_FAILED);
        return parseRecommendedPlaylists(json, limit);
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

    public AlbumDetailView fetchAlbumDetail(String id, int page, int pageSize) {
        Request req = new Request.Builder()
                .url("https://music.163.com/api/album/" + id)
                .header("Referer", REFERER)
                .header("Cookie", COOKIE)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        Map<String, Object> json = callJson(req, MusicErrorCode.UPSTREAM_PLAYLIST_FAILED);
        Map<String, Object> album = asMap(json.get("album"));
        List<SongSearchItem> all = new ArrayList<>();
        Object songsObj = album == null ? null : album.get("songs");
        if (songsObj instanceof List) {
            for (Object row : (List<?>) songsObj) {
                Map<String, Object> song = asMap(row);
                if (song != null) all.add(toSongItem(song));
            }
        }
        int from = Math.min(all.size(), Math.max(0, (page - 1) * pageSize));
        int to = Math.min(all.size(), from + pageSize);
        List<SongSearchItem> songs = new ArrayList<>(all.subList(from, to));
        Object artists = album == null ? null : album.get("artists");
        if (!(artists instanceof List) && album != null) artists = singletonArtist(album.get("artist"));
        return new AlbumDetailView(id, MusicSource.NETEASE,
                album == null ? null : asString(album.get("name")),
                album == null ? null : asString(album.get("picUrl")),
                album == null ? null : asString(album.get("description")),
                joinArtists(artists),
                album == null ? null : formatDate(album.get("publishTime")),
                page, pageSize,
                album == null ? null : firstNonNull(asInt(album.get("size")), all.size()), songs);
    }

    public ArtistWorksView fetchArtistWorks(String artistId, String type, int page, int pageSize) {
        int offset = Math.max(0, (page - 1) * pageSize);
        if ("album".equals(type)) {
            HttpUrl url = HttpUrl.parse("https://music.163.com/api/artist/albums/" + artistId).newBuilder()
                    .addQueryParameter("offset", String.valueOf(offset))
                    .addQueryParameter("limit", String.valueOf(pageSize))
                    .build();
            Map<String, Object> json = callJson(officialGet(url), MusicErrorCode.UPSTREAM_SEARCH_FAILED);
            List<AlbumSummaryItem> albums = new ArrayList<>();
            Object rows = json.get("hotAlbums");
            if (rows instanceof List) {
                for (Object row : (List<?>) rows) {
                    Map<String, Object> album = asMap(row);
                    if (album == null) continue;
                    Object artists = album.get("artists");
                    if (!(artists instanceof List)) artists = singletonArtist(album.get("artist"));
                    List<String> artistIds = idsFromMaps(artists, "id", "artistId");
                    albums.add(new AlbumSummaryItem(asString(album.get("id")), MusicSource.NETEASE,
                            asString(album.get("name")), joinArtists(artists),
                            artistIds.isEmpty() ? null : artistIds.get(0), artistIds,
                            asString(album.get("picUrl")), formatDate(album.get("publishTime")),
                            asInt(album.get("size")), asString(album.get("description"))));
                }
            }
            Map<String, Object> artist = asMap(json.get("artist"));
            Integer total = firstNonNull(asInt(json.get("total")), artist == null ? null : asInt(artist.get("albumSize")));
            if (total == null && !Boolean.TRUE.equals(json.get("more"))) total = offset + albums.size();
            return new ArtistWorksView(artistId, MusicSource.NETEASE, "album", page, pageSize,
                    total, Collections.emptyList(), albums);
        }

        HttpUrl url = HttpUrl.parse("https://music.163.com/api/v1/artist/songs").newBuilder()
                .addQueryParameter("id", artistId)
                .addQueryParameter("offset", String.valueOf(offset))
                .addQueryParameter("limit", String.valueOf(pageSize))
                .addQueryParameter("order", "hot")
                .build();
        Map<String, Object> json = callJson(officialGet(url), MusicErrorCode.UPSTREAM_SEARCH_FAILED);
        List<SongSearchItem> songs = new ArrayList<>();
        Object rows = json.get("songs");
        if (rows instanceof List) {
            for (Object row : (List<?>) rows) {
                Map<String, Object> song = asMap(row);
                if (song != null) songs.add(toSongItem(song));
            }
        }
        return new ArtistWorksView(artistId, MusicSource.NETEASE, "song", page, pageSize,
                asInt(json.get("total")), songs, Collections.emptyList());
    }

    private Request officialGet(HttpUrl url) {
        return new Request.Builder().url(url).header("Referer", REFERER)
                .header("Cookie", COOKIE).header("User-Agent", USER_AGENT).get().build();
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
            p.setTrackCount(asInt(s.get("trackCount")));
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
    private PlaylistListView parseRecommendedPlaylists(Map<String, Object> json, int limit) {
        PlaylistListView v = new PlaylistListView(MusicSource.NETEASE, "recommend", "recommend", 1, limit, 0L,
                Collections.emptyList());
        Object resultObj = json.get("result");
        if (!(resultObj instanceof List)) return v;

        List<PlaylistItem> out = new ArrayList<>();
        for (Object item : (List<?>) resultObj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> s = (Map<String, Object>) item;
            PlaylistItem p = new PlaylistItem();
            p.setSource(MusicSource.NETEASE);
            p.setId(asString(s.get("id")));
            p.setName(asString(s.get("name")));
            p.setCoverUrl(firstNonEmpty(asString(s.get("picUrl")), asString(s.get("coverImgUrl"))));
            p.setDescription(firstNonEmpty(asString(s.get("copywriter")), asString(s.get("description"))));
            p.setTrackCount(asInt(s.get("trackCount")));
            p.setPlayCount(asLongObj(s.get("playCount")));
            if (p.getId() != null && !p.getId().isEmpty()) out.add(p);
        }
        v.setTotal((long) out.size());
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

        Integer trackCount = asInt(playlist.get("trackCount"));
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

        Integer trackCount = asInt(playlist.get("trackCount"));
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
                item.setTrackCount(asInt(s.get("musicSize")));
                break;
            case ALBUM:
                item.setCoverUrl(asString(s.get("picUrl")));
                item.setTrackCount(asInt(s.get("size")));
                Object albumArtists = firstValue(s.get("artists"), singletonArtist(s.get("artist")));
                item.setArtist(joinArtists(albumArtists));
                List<String> albumArtistIds = idsFromMaps(albumArtists, "id", "artistId");
                item.setArtistIds(albumArtistIds);
                item.setArtistId(albumArtistIds.isEmpty() ? null : albumArtistIds.get(0));
                break;
            case PLAYLIST:
                item.setCoverUrl(asString(s.get("coverImgUrl")));
                item.setTrackCount(asInt(s.get("trackCount")));
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
        setArtistIds(v, artists);
        Map<String, Object> al = asMap(s.get("al"));
        if (al == null) al = asMap(s.get("album"));
        if (al != null) {
            v.setAlbum(asString(al.get("name")));
            v.setAlbumId(asString(al.get("id")));
            v.setCoverUrl(firstNonEmpty(asString(al.get("picUrl")), neteaseImageUrl(al.get("picId"))));
        }
        Long dt = asLongObj(s.get("dt"));
        if (dt == null) dt = asLongObj(s.get("duration"));
        if (dt != null) {
            v.setDurationMs(dt);
            v.setDurationSec((int) (dt / 1000L));
        }
        v.setAvailableQualities(qualitiesFromSong(s));
        return v;
    }

    private List<String> qualitiesFromSong(Map<String, Object> s) {
        List<String> q = new ArrayList<>();
        if (firstValue(s.get("l"), s.get("lMusic")) != null) q.add("128k");
        if (firstValue(s.get("h"), s.get("hMusic")) != null) q.add("320k");
        if (firstValue(s.get("sq"), s.get("sqMusic")) != null) q.add("flac");
        if (firstValue(s.get("hr"), s.get("hrMusic")) != null) q.add("flac24bit");
        return q;
    }

    private static Integer firstNonNull(Integer first, Integer second) {
        return first != null ? first : second;
    }

    private static String formatDate(Object epochMillis) {
        Long value = asLongObj(epochMillis);
        if (value == null) return asString(epochMillis);
        return Instant.ofEpochMilli(value).atZone(ZoneOffset.ofHours(8)).toLocalDate().toString();
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

    private void setArtistIds(SongSearchItem item, Object artists) {
        List<String> ids = idsFromMaps(artists, "id", "artistId");
        item.setArtistIds(ids);
        item.setArtistId(ids.isEmpty() ? null : ids.get(0));
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

}
