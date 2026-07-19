package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.OkHttpConfig;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.MusicSearchType;
import com.example.website.dto.music.SearchCollectionItem;
import com.example.website.dto.music.PlaylistDetailView;
import com.example.website.dto.music.PlaylistItem;
import com.example.website.dto.music.PlaylistListView;
import com.example.website.dto.music.SongSearchItem;
import com.example.website.dto.music.ToplistDetailView;
import com.example.website.dto.music.ToplistItem;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class QqMusicClient {

    private static final String SEARCH_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String SEARCH_CP_URL = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp";
    private static final String LYRIC_URL  = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg";
    private static final String PLAYLIST_LIST_URL = "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg";
    private static final String PLAYLIST_DETAIL_URL = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg";
    private static final String REFERER    = "https://y.qq.com/";
    private static final MediaType JSON    = MediaType.get("application/json; charset=utf-8");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    @Qualifier(OkHttpConfig.CLIENT_MUSIC)
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public List<SongSearchItem> search(String keyword, int page, int pageSize) {
        HttpUrl url = HttpUrl.parse(SEARCH_CP_URL).newBuilder()
                .addQueryParameter("g_tk", "5381")
                .addQueryParameter("uin", "0")
                .addQueryParameter("format", "json")
                .addQueryParameter("inCharset", "utf-8")
                .addQueryParameter("outCharset", "utf-8")
                .addQueryParameter("notice", "0")
                .addQueryParameter("platform", "h5")
                .addQueryParameter("needNewCode", "1")
                .addQueryParameter("w", keyword)
                .addQueryParameter("zhidaqu", "1")
                .addQueryParameter("catZhida", "1")
                .addQueryParameter("t", "0")
                .addQueryParameter("flag", "1")
                .addQueryParameter("ie", "utf-8")
                .addQueryParameter("sem", "1")
                .addQueryParameter("aggr", "0")
                .addQueryParameter("perpage", String.valueOf(pageSize))
                .addQueryParameter("n", String.valueOf(pageSize))
                .addQueryParameter("p", String.valueOf(page))
                .addQueryParameter("remoteplace", "txt.mqq.all")
                .build();

        Request req = new Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .get()
                .build();

        Map<String, Object> json = callJson(req, MusicErrorCode.UPSTREAM_SEARCH_FAILED);
        ensureQqJsonSuccess(json, MusicErrorCode.UPSTREAM_SEARCH_FAILED);
        return parseCpSearch(json);
    }

    /** QQ's public CP endpoint exposes artist/album search; it does not expose keyword playlist search. */
    public NeteaseMusicClient.CollectionSearchResult searchCollections(String keyword, MusicSearchType type, int page, int pageSize) {
        if (type == null || type == MusicSearchType.SONG || type == MusicSearchType.PLAYLIST) {
            return new NeteaseMusicClient.CollectionSearchResult(0L, Collections.emptyList());
        }
        Map<String, Object> json;
        if (type == MusicSearchType.ARTIST) {
            HttpUrl url = HttpUrl.parse("https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg").newBuilder()
                    .addQueryParameter("format", "json").addQueryParameter("inCharset", "utf8")
                    .addQueryParameter("outCharset", "utf-8").addQueryParameter("key", keyword).build();
            Request req = new Request.Builder().url(url).header("Referer", REFERER).header("User-Agent", "Mozilla/5.0").get().build();
            json = callJson(req, MusicErrorCode.UPSTREAM_SEARCH_FAILED);
            return parseQqArtists(json, type, page, pageSize);
        }
        json = searchCpJson(keyword, page, pageSize, "8");
        ensureQqJsonSuccess(json, MusicErrorCode.UPSTREAM_SEARCH_FAILED);
        return parseQqAlbums(json, type);
    }

    private Map<String, Object> searchCpJson(String keyword, int page, int pageSize, String t) {
        HttpUrl url = HttpUrl.parse(SEARCH_CP_URL).newBuilder()
                .addQueryParameter("g_tk", "5381").addQueryParameter("uin", "0").addQueryParameter("format", "json")
                .addQueryParameter("inCharset", "utf-8").addQueryParameter("outCharset", "utf-8").addQueryParameter("notice", "0")
                .addQueryParameter("platform", "h5").addQueryParameter("needNewCode", "1").addQueryParameter("w", keyword)
                .addQueryParameter("zhidaqu", "1").addQueryParameter("catZhida", "1").addQueryParameter("t", t)
                .addQueryParameter("flag", "1").addQueryParameter("ie", "utf-8").addQueryParameter("sem", "1")
                .addQueryParameter("aggr", "0").addQueryParameter("perpage", String.valueOf(pageSize)).addQueryParameter("n", String.valueOf(pageSize))
                .addQueryParameter("p", String.valueOf(page)).addQueryParameter("remoteplace", "txt.mqq.all").build();
        Request req = new Request.Builder().url(url).header("Referer", REFERER).header("User-Agent", "Mozilla/5.0").header("Accept", "application/json").get().build();
        return callJson(req, MusicErrorCode.UPSTREAM_SEARCH_FAILED);
    }

    @SuppressWarnings("unchecked")
    private NeteaseMusicClient.CollectionSearchResult parseQqArtists(Map<String, Object> json, MusicSearchType type, int page, int pageSize) {
        Map<String, Object> data = asMap(json.get("data"));
        Map<String, Object> singer = data == null ? null : asMap(data.get("singer"));
        Object rows = singer == null ? null : singer.get("itemlist");
        List<SearchCollectionItem> out = new ArrayList<>();
        if (rows instanceof List) for (Object row : (List<?>) rows) if (row instanceof Map) {
            Map<String, Object> s = (Map<String, Object>) row;
            SearchCollectionItem item = new SearchCollectionItem(firstNonEmpty(asString(s.get("mid")), asString(s.get("id")), asString(s.get("docid"))), MusicSource.QQ, type, firstNonEmpty(asString(s.get("name")), asString(s.get("singer"))), null, null, asString(s.get("pic")), null, null);
            if (item.getId() != null && item.getName() != null) out.add(item);
        }
        int from = Math.max(0, (page - 1) * pageSize);
        return new NeteaseMusicClient.CollectionSearchResult((long) out.size(), out.subList(Math.min(from, out.size()), Math.min(out.size(), from + pageSize)));
    }

    @SuppressWarnings("unchecked")
    private NeteaseMusicClient.CollectionSearchResult parseQqAlbums(Map<String, Object> json, MusicSearchType type) {
        Map<String, Object> data = asMap(json.get("data"));
        Map<String, Object> album = data == null ? null : asMap(data.get("album"));
        Object rows = album == null ? null : album.get("list");
        List<SearchCollectionItem> out = new ArrayList<>();
        if (rows instanceof List) for (Object row : (List<?>) rows) if (row instanceof Map) {
            Map<String, Object> s = (Map<String, Object>) row;
            String mid = firstNonEmpty(asString(s.get("albumMID")), asString(s.get("albumMid")), asString(s.get("mid")), asString(s.get("albumID")), asString(s.get("id")));
            String cover = asString(s.get("pic"));
            if ((cover == null || cover.isEmpty()) && mid != null) cover = "https://y.gtimg.cn/music/photo_new/T002R500x500M000" + mid + ".jpg";
            SearchCollectionItem item = new SearchCollectionItem(mid, MusicSource.QQ, type, firstNonEmpty(asString(s.get("albumName")), asString(s.get("name"))), firstNonEmpty(asString(s.get("singerName")), asString(s.get("singer"))), null, cover, null, null);
            if (item.getId() != null && item.getName() != null) out.add(item);
        }
        Long total = album == null ? 0L : asLongObj(album.get("totalnum"));
        return new NeteaseMusicClient.CollectionSearchResult(total == null ? (long) out.size() : total, out);
    }

    public String fetchLyric(String songMid) {
        HttpUrl url = HttpUrl.parse(LYRIC_URL).newBuilder()
                .addQueryParameter("songmid", songMid)
                .addQueryParameter("format", "json")
                .addQueryParameter("nobase64", "1")
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .get()
                .build();
        Map<String, Object> json = callJson(req, MusicErrorCode.UPSTREAM_LYRIC_FAILED);
        Object lyric = json.get("lyric");
        return lyric instanceof String ? (String) lyric : null;
    }

    /**
     * Fetch song metadata (name/artist/album) by songmid. Used by the play
     * fallback path in {@link MusicService} when the primary source's
     * tf-pay call fails and we need a keyword to search other sources.
     * Returns null if upstream errors.
     */
    @SuppressWarnings("unchecked")
    public SongSearchItem fetchSongInfo(String songMid) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("song_mid", songMid);
        Map<String, Object> req1 = new LinkedHashMap<>();
        req1.put("module", "music.pf_song_detail_svr");
        req1.put("method", "get_song_detail_yqq");
        req1.put("param", param);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("req_1", req1);

        String bodyJson;
        try {
            bodyJson = objectMapper.writer()
                    .with(JsonGenerator.Feature.ESCAPE_NON_ASCII)
                    .writeValueAsString(body);
        } catch (Exception e) {
            log.debug("QQ songInfo body build failed: {}", e.getMessage());
            return null;
        }

        HttpUrl url = HttpUrl.parse(SEARCH_URL).newBuilder()
                .addQueryParameter("loginUin", "0")
                .addQueryParameter("hostUin", "0")
                .addQueryParameter("format", "json")
                .addQueryParameter("inCharset", "utf-8")
                .addQueryParameter("outCharset", "utf-8")
                .addQueryParameter("notice", "0")
                .addQueryParameter("platform", "wk_v15.json")
                .addQueryParameter("needNewCode", "0")
                .build();

        Request req = new Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .post(RequestBody.create(bodyJson, JSON))
                .build();

        Map<String, Object> json;
        try {
            json = callJson(req, MusicErrorCode.UPSTREAM_PLAY_FAILED);
        } catch (Exception e) {
            log.debug("QQ songInfo lookup failed for mid={}: {}", songMid, e.getMessage());
            return null;
        }

        Object req1Obj = json.get("req_1");
        if (!(req1Obj instanceof Map)) return null;
        Object data = ((Map<?, ?>) req1Obj).get("data");
        if (!(data instanceof Map)) return null;
        Object track = ((Map<?, ?>) data).get("track_info");
        if (!(track instanceof Map)) return null;
        Map<String, Object> t = (Map<String, Object>) track;

        SongSearchItem v = new SongSearchItem();
        v.setSource(MusicSource.QQ);
        v.setId(firstNonEmpty(asString(t.get("mid")), songMid));
        v.setName(firstNonEmpty(asString(t.get("title")), asString(t.get("name"))));
        v.setArtist(joinSingers(t.get("singer")));
        Map<String, Object> album = asMap(t.get("album"));
        if (album != null) {
            v.setAlbum(asString(album.get("name")));
            v.setAlbumId(asString(album.get("mid")));
        }
        return (v.getName() == null || v.getName().isEmpty()) ? null : v;
    }

    /**
     * QQ toplist list. Posts to {@code musicu.fcg} with
     * {@code musicToplist.ToplistInfoServer/GetAll} and flattens the
     * {@code group[].toplist[]} structure into a single list.
     */
    public List<ToplistItem> fetchToplists() {
        Map<String, Object> req1 = new LinkedHashMap<>();
        req1.put("module", "musicToplist.ToplistInfoServer");
        req1.put("method", "GetAll");
        req1.put("param", new LinkedHashMap<String, Object>());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("req_1", req1);

        Map<String, Object> json = musicUPost(body, MusicErrorCode.UPSTREAM_TOPLIST_FAILED);
        return parseToplists(json);
    }

    /**
     * QQ toplist detail ({@code ToplistInfoServer/GetDetail}). Supports native
     * offset/num pagination — we convert {@code page} (1-based) to offset.
     * For "新歌榜" pass {@code topId="27"}.
     */
    public ToplistDetailView fetchToplistDetail(String topId, int page, int pageSize) {
        int offset = Math.max(0, (page - 1) * pageSize);
        int topIdInt;
        try {
            topIdInt = Integer.parseInt(topId);
        } catch (NumberFormatException e) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TOPLIST_FAILED,
                    "qq topId must be integer: " + topId);
        }

        Map<String, Object> param = new LinkedHashMap<>();
        param.put("topId", topIdInt);
        param.put("offset", offset);
        param.put("num", pageSize);
        param.put("period", "");

        Map<String, Object> req1 = new LinkedHashMap<>();
        req1.put("module", "musicToplist.ToplistInfoServer");
        req1.put("method", "GetDetail");
        req1.put("param", param);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("req_1", req1);

        Map<String, Object> json = musicUPost(body, MusicErrorCode.UPSTREAM_TOPLIST_FAILED);
        return parseToplistDetail(json, topId, page, pageSize);
    }

    public PlaylistListView fetchPlaylists(String category, String order, int page, int pageSize) {
        int rawOffset = Math.max(0, (page - 1) * pageSize);
        int batchSize = Math.min(60, Math.max(pageSize * 3, pageSize));

        PlaylistListView out = new PlaylistListView();
        out.setSource(MusicSource.QQ);
        out.setCategory(category);
        out.setOrder(order);
        out.setPage(page);
        out.setPageSize(pageSize);
        out.setList(Collections.emptyList());

        List<PlaylistItem> accessible = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int attempt = 0; attempt < 3 && accessible.size() < pageSize; attempt++) {
            Map<String, Object> json = fetchPlaylistListPageJson(category, order, rawOffset, batchSize);
            PlaylistListView batch = parsePlaylistList(json, category, order, page, pageSize);
            if (out.getTotal() == null) out.setTotal(batch.getTotal());

            List<PlaylistItem> candidates = batch.getList();
            if (candidates == null || candidates.isEmpty()) break;

            for (PlaylistItem item : candidates) {
                if (item == null || item.getId() == null || item.getId().isEmpty()) continue;
                if (!seen.add(item.getId())) continue;
                if (!isPlaylistAccessible(item.getId())) continue;
                accessible.add(item);
                if (accessible.size() >= pageSize) break;
            }
            rawOffset += batchSize;
        }

        out.setList(accessible);
        return out;
    }

    public PlaylistDetailView fetchPlaylistDetail(String disstid, int page, int pageSize) {
        Map<String, Object> json = fetchPlaylistDetailJson(disstid);
        return parsePlaylistDetail(json, disstid, page, pageSize);
    }

    /** Shared {@code musicu.fcg} POST path. Handles body serialization (forces
     *  non-ASCII escape, same rationale as search — QQ rejects raw UTF-8),
     *  adds the standard query params, and unwraps via {@link #callJson}. */
    private Map<String, Object> musicUPost(Map<String, Object> body, MusicErrorCode onFail) {
        String bodyJson;
        try {
            bodyJson = objectMapper.writer()
                    .with(JsonGenerator.Feature.ESCAPE_NON_ASCII)
                    .writeValueAsString(body);
        } catch (Exception e) {
            throw new MusicBusinessException(onFail,
                    "failed to build qq musicu body: " + e.getMessage(), e);
        }
        HttpUrl url = HttpUrl.parse(SEARCH_URL).newBuilder()
                .addQueryParameter("loginUin", "0")
                .addQueryParameter("hostUin", "0")
                .addQueryParameter("format", "json")
                .addQueryParameter("inCharset", "utf-8")
                .addQueryParameter("outCharset", "utf-8")
                .addQueryParameter("notice", "0")
                .addQueryParameter("platform", "wk_v15.json")
                .addQueryParameter("needNewCode", "0")
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .post(RequestBody.create(bodyJson, JSON))
                .build();
        Map<String, Object> json = callJson(req, onFail);
        ensureMusicUSuccess(json, onFail);
        return json;
    }

    @SuppressWarnings("unchecked")
    private List<ToplistItem> parseToplists(Map<String, Object> json) {
        Map<String, Object> root = unwrapMusicUData(json);
        if (root == null) return Collections.emptyList();
        Object groupObj = root.get("group");
        if (!(groupObj instanceof List)) return Collections.emptyList();

        List<ToplistItem> out = new ArrayList<>();
        for (Object g : (List<?>) groupObj) {
            if (!(g instanceof Map)) continue;
            Object tops = ((Map<?, ?>) g).get("toplist");
            if (!(tops instanceof List)) continue;
            for (Object t : (List<?>) tops) {
                if (!(t instanceof Map)) continue;
                Map<String, Object> s = (Map<String, Object>) t;
                ToplistItem item = new ToplistItem();
                item.setSource(MusicSource.QQ);
                item.setId(asString(s.get("topId")));
                item.setName(firstNonEmpty(asString(s.get("title")), asString(s.get("titleDetail"))));
                item.setCoverUrl(firstNonEmpty(
                        asString(s.get("headPicUrl")),
                        asString(s.get("picUrl")),
                        asString(s.get("frontPicUrl"))));
                item.setDescription(asString(s.get("titleDetail")));
                item.setUpdateTime(asString(s.get("updateTime")));
                if (item.getId() != null && !item.getId().isEmpty()) out.add(item);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private ToplistDetailView parseToplistDetail(Map<String, Object> json, String reqId, int page, int pageSize) {
        ToplistDetailView v = new ToplistDetailView();
        v.setId(reqId);
        v.setSource(MusicSource.QQ);
        v.setPage(page);
        v.setPageSize(pageSize);
        v.setList(Collections.emptyList());

        Map<String, Object> root = unwrapMusicUData(json);
        if (root == null) return v;

        String topId = asString(root.get("topId"));
        if (topId != null && !topId.isEmpty()) v.setId(topId);
        v.setName(firstNonEmpty(asString(root.get("title")), asString(root.get("name"))));
        v.setCoverUrl(firstNonEmpty(
                asString(root.get("frontPicUrl")),
                asString(root.get("headPicUrl")),
                asString(root.get("picUrl"))));
        v.setDescription(asString(root.get("titleDetail")));
        v.setUpdateTime(asString(root.get("updateTime")));

        Integer total = asInt(root.get("songTotalNum"));
        if (total == null) total = asInt(root.get("total_song_num"));
        if (total == null) total = asInt(root.get("totalNum"));
        v.setTotal(total);

        Object songList = root.get("songInfoList");
        if (!(songList instanceof List)) {
            Map<String, Object> nested = asMap(root.get("data"));
            if (nested != null) songList = nested.get("songInfoList");
        }
        if (!(songList instanceof List)) songList = root.get("song");
        if (!(songList instanceof List)) {
            Map<String, Object> nested = asMap(root.get("data"));
            if (nested != null) songList = nested.get("song");
        }
        if (!(songList instanceof List)) return v;

        List<SongSearchItem> out = new ArrayList<>();
        for (Object item : (List<?>) songList) {
            if (!(item instanceof Map)) continue;
            SongSearchItem si = toSongItem((Map<String, Object>) item);
            if (si.getId() != null && !si.getId().isEmpty()) out.add(si);
        }
        v.setList(out);
        return v;
    }

    @SuppressWarnings("unchecked")
    private PlaylistListView parsePlaylistList(Map<String, Object> json,
                                               String category,
                                               String order,
                                               int page,
                                               int pageSize) {
        PlaylistListView v = new PlaylistListView();
        v.setSource(MusicSource.QQ);
        v.setCategory(category);
        v.setOrder(order);
        v.setPage(page);
        v.setPageSize(pageSize);
        v.setList(Collections.emptyList());

        Object dataObj = json.get("data");
        if (!(dataObj instanceof Map)) return v;
        Map<String, Object> data = (Map<String, Object>) dataObj;

        Long total = asLongObj(data.get("sum"));
        if (total != null) v.setTotal(total);

        Object listObj = data.get("list");
        if (!(listObj instanceof List)) return v;

        List<PlaylistItem> out = new ArrayList<>();
        for (Object item : (List<?>) listObj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> s = (Map<String, Object>) item;
            PlaylistItem p = new PlaylistItem();
            p.setSource(MusicSource.QQ);
            p.setId(asString(s.get("dissid")));
            p.setName(asString(s.get("dissname")));
            p.setCoverUrl(asString(s.get("imgurl")));
            p.setDescription(asString(s.get("introduction")));
            p.setPlayCount(asLongObj(s.get("listennum")));
            Map<String, Object> creator = asMap(s.get("creator"));
            if (creator != null) {
                p.setCreatorName(asString(creator.get("name")));
            }
            if (p.getId() != null && !p.getId().isEmpty()) out.add(p);
        }
        v.setList(out);
        return v;
    }

    @SuppressWarnings("unchecked")
    private PlaylistDetailView parsePlaylistDetail(Map<String, Object> json, String reqId, int page, int pageSize) {
        PlaylistDetailView v = new PlaylistDetailView();
        v.setId(reqId);
        v.setSource(MusicSource.QQ);
        v.setPage(page);
        v.setPageSize(pageSize);
        v.setList(Collections.emptyList());

        Object cdlistObj = json.get("cdlist");
        if (!(cdlistObj instanceof List) || ((List<?>) cdlistObj).isEmpty()) return v;
        Object first = ((List<?>) cdlistObj).get(0);
        if (!(first instanceof Map)) return v;
        Map<String, Object> playlist = (Map<String, Object>) first;

        String disstid = asString(playlist.get("disstid"));
        if (disstid != null && !disstid.isEmpty()) v.setId(disstid);
        v.setName(firstNonEmpty(asString(playlist.get("dissname")), asString(playlist.get("title"))));
        v.setCoverUrl(firstNonEmpty(asString(playlist.get("logo")), asString(playlist.get("picurl"))));
        v.setDescription(unescapeHtml(asString(playlist.get("desc"))));
        v.setCreatorName(firstNonEmpty(asString(playlist.get("nickname")), asString(playlist.get("nick"))));
        Long playCount = asLongObj(playlist.get("visitnum"));
        if (playCount != null) v.setPlayCount(playCount);
        Object updateTime = playlist.get("mtime");
        if (updateTime == null || "0".equals(asString(updateTime))) updateTime = playlist.get("ctime");
        if (updateTime != null) v.setUpdateTime(asString(updateTime));

        Object totalObj = playlist.get("songnum");
        if (totalObj == null) totalObj = playlist.get("total_song_num");
        Integer total = asInt(totalObj);
        v.setTotal(total);

        Object songListObj = playlist.get("songlist");
        if (!(songListObj instanceof List)) return v;
        List<?> songs = (List<?>) songListObj;
        int from = Math.max(0, (page - 1) * pageSize);
        if (from >= songs.size()) return v;
        int to = Math.min(songs.size(), from + pageSize);

        List<SongSearchItem> out = new ArrayList<>();
        for (Object item : songs.subList(from, to)) {
            if (!(item instanceof Map)) continue;
            SongSearchItem si = toSongItem((Map<String, Object>) item);
            if (si.getId() != null && !si.getId().isEmpty()) out.add(si);
        }
        v.setList(out);
        return v;
    }

    private Map<String, Object> fetchPlaylistListPageJson(String category, String order, int sin, int count) {
        int safeSin = Math.max(0, sin);
        int ein = safeSin + Math.max(1, count) - 1;
        HttpUrl url = HttpUrl.parse(PLAYLIST_LIST_URL).newBuilder()
                .addQueryParameter("picmid", "1")
                .addQueryParameter("rnd", "0.123")
                .addQueryParameter("g_tk", "732560869")
                .addQueryParameter("loginUin", "0")
                .addQueryParameter("hostUin", "0")
                .addQueryParameter("format", "json")
                .addQueryParameter("inCharset", "utf8")
                .addQueryParameter("outCharset", "utf-8")
                .addQueryParameter("notice", "0")
                .addQueryParameter("platform", "yqq.json")
                .addQueryParameter("needNewCode", "0")
                .addQueryParameter("categoryId", qqPlaylistCategoryId(category))
                .addQueryParameter("sortId", qqPlaylistSortId(order))
                .addQueryParameter("sin", String.valueOf(safeSin))
                .addQueryParameter("ein", String.valueOf(ein))
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .get()
                .build();
        return callJson(req, MusicErrorCode.UPSTREAM_PLAYLIST_FAILED);
    }

    private Map<String, Object> fetchPlaylistDetailJson(String disstid) {
        HttpUrl url = HttpUrl.parse(PLAYLIST_DETAIL_URL).newBuilder()
                .addQueryParameter("type", "1")
                .addQueryParameter("json", "1")
                .addQueryParameter("utf8", "1")
                .addQueryParameter("onlysong", "0")
                .addQueryParameter("new_format", "1")
                .addQueryParameter("disstid", disstid)
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .get()
                .build();
        Map<String, Object> json = callJsonp(req, MusicErrorCode.UPSTREAM_PLAYLIST_FAILED);
        ensureQqJsonSuccess(json, MusicErrorCode.UPSTREAM_PLAYLIST_FAILED);
        return json;
    }

    /**
     * musicu.fcg responses wrap payloads under {@code req_1.data}, but some
     * modules add a further {@code data} nesting. Strip both layers so callers
     * get a merged view of the outer and inner maps. Some QQ modules place
     * metadata in {@code req_1.data.data} but keep sibling arrays such as
     * {@code songInfoList} directly under {@code req_1.data}; those siblings
     * must not be discarded.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapMusicUData(Map<String, Object> json) {
        Object req1 = json.get("req_1");
        if (!(req1 instanceof Map)) return null;
        Object data = ((Map<?, ?>) req1).get("data");
        if (!(data instanceof Map)) return null;
        Object inner = ((Map<?, ?>) data).get("data");
        if (!(inner instanceof Map)) return (Map<String, Object>) data;

        Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) data);
        merged.putAll((Map<String, Object>) inner);
        return merged;
    }

    private void ensureMusicUSuccess(Map<String, Object> json, MusicErrorCode onFail) {
        Integer code = asInt(json.get("code"));
        if (code != null && code != 0) {
            throw new MusicBusinessException(onFail, "qq musicu business error code=" + code);
        }

        Map<String, Object> req1 = asMap(json.get("req_1"));
        if (req1 == null) return;

        Integer req1Code = asInt(req1.get("code"));
        if (req1Code == null || req1Code == 0) return;

        String message = firstNonEmpty(
                asString(req1.get("message")),
                asString(req1.get("msg")),
                asString(json.get("message")));
        if (message == null || message.isEmpty()) {
            throw new MusicBusinessException(onFail, "qq musicu business error req_1.code=" + req1Code);
        }
        throw new MusicBusinessException(onFail,
                "qq musicu business error req_1.code=" + req1Code + ", message=" + message);
    }

    @SuppressWarnings("unchecked")
    private List<SongSearchItem> parseSearch(Map<String, Object> json) {
        Object req1 = json.get("req_1");
        if (!(req1 instanceof Map)) return Collections.emptyList();
        Object data = ((Map<?, ?>) req1).get("data");
        if (!(data instanceof Map)) return Collections.emptyList();
        Object bodyObj = ((Map<?, ?>) data).get("body");
        if (!(bodyObj instanceof Map)) return Collections.emptyList();
        Object song = ((Map<?, ?>) bodyObj).get("song");
        if (!(song instanceof Map)) return Collections.emptyList();
        Object listObj = ((Map<?, ?>) song).get("list");
        if (!(listObj instanceof List)) return Collections.emptyList();

        List<SongSearchItem> out = new ArrayList<>();
        for (Object item : (List<?>) listObj) {
            if (!(item instanceof Map)) continue;
            SongSearchItem v = toSongItem((Map<String, Object>) item);
            if (v.getId() != null && !v.getId().isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<SongSearchItem> parseCpSearch(Map<String, Object> json) {
        Map<String, Object> data = asMap(json.get("data"));
        if (data == null) return Collections.emptyList();
        Map<String, Object> song = asMap(data.get("song"));
        if (song == null) return Collections.emptyList();
        Object listObj = song.get("list");
        if (!(listObj instanceof List)) return Collections.emptyList();

        List<SongSearchItem> out = new ArrayList<>();
        for (Object item : (List<?>) listObj) {
            if (!(item instanceof Map)) continue;
            SongSearchItem v = toCpSongItem((Map<String, Object>) item);
            if (v.getId() != null && !v.getId().isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private SongSearchItem toCpSongItem(Map<String, Object> s) {
        SongSearchItem v = new SongSearchItem();
        v.setSource(MusicSource.QQ);
        v.setId(firstNonEmpty(asString(s.get("songmid")), asString(s.get("songid"))));
        v.setName(firstNonEmpty(asString(s.get("songname")), asString(s.get("songorig")), asString(s.get("name"))));
        v.setArtist(joinSingers(s.get("singer")));
        v.setAlbum(firstNonEmpty(asString(s.get("albumname")), asString(s.get("albumdesc"))));
        v.setAlbumId(asString(s.get("albummid")));
        String albumMid = asString(s.get("albummid"));
        if (albumMid != null && !albumMid.isEmpty()) {
            v.setCoverUrl("https://y.gtimg.cn/music/photo_new/T002R500x500M000" + albumMid + ".jpg");
        }
        Integer interval = asInt(s.get("interval"));
        if (interval != null) {
            v.setDurationSec(interval);
            v.setDurationMs((long) interval * 1000L);
        }
        v.setAvailableQualities(qualitiesFromFile(asMap(s.get("file"))));
        if (v.getAvailableQualities() == null || v.getAvailableQualities().isEmpty()) {
            List<String> q = new ArrayList<>();
            if (asLong(s.get("size128")) > 0 || asLong(s.get("size128mp3")) > 0) q.add("128k");
            if (asLong(s.get("size320")) > 0 || asLong(s.get("size320mp3")) > 0) q.add("320k");
            if (asLong(s.get("sizeflac")) > 0) q.add("flac");
            v.setAvailableQualities(q);
        }
        return v;
    }

    private SongSearchItem toSongItem(Map<String, Object> s) {
        SongSearchItem v = new SongSearchItem();
        v.setSource(MusicSource.QQ);
        v.setId(asString(s.get("mid")));
        v.setName(firstNonEmpty(asString(s.get("title")), asString(s.get("name"))));
        v.setArtist(joinSingers(s.get("singer")));
        Map<String, Object> album = asMap(s.get("album"));
        if (album != null) {
            v.setAlbum(firstNonEmpty(asString(album.get("title")), asString(album.get("name"))));
            v.setAlbumId(asString(album.get("mid")));
            String mid = asString(album.get("mid"));
            if (mid != null && !mid.isEmpty()) {
                v.setCoverUrl("https://y.gtimg.cn/music/photo_new/T002R500x500M000" + mid + ".jpg");
            }
        }
        Integer interval = asInt(s.get("interval"));
        if (interval != null) {
            v.setDurationSec(interval);
            v.setDurationMs((long) interval * 1000L);
        }
        v.setAvailableQualities(qualitiesFromFile(asMap(s.get("file"))));
        return v;
    }

    private List<String> qualitiesFromFile(Map<String, Object> file) {
        List<String> q = new ArrayList<>();
        if (file == null) return q;
        if (asLong(file.get("size_128mp3")) > 0) q.add("128k");
        if (asLong(file.get("size_320mp3")) > 0) q.add("320k");
        if (asLong(file.get("size_flac")) > 0) q.add("flac");
        return q;
    }

    @SuppressWarnings("unchecked")
    private String joinSingers(Object singerObj) {
        if (!(singerObj instanceof List)) return null;
        StringBuilder sb = new StringBuilder();
        for (Object s : (List<?>) singerObj) {
            if (!(s instanceof Map)) continue;
            String n = asString(((Map<String, Object>) s).get("name"));
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
                log.warn("QQ upstream non-2xx status={} body={}", resp.code(), truncate(raw));
                throw new MusicBusinessException(onFail,
                        "qq upstream returned " + resp.code());
            }
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (IOException e) {
            log.warn("QQ upstream I/O failed: {}", e.getMessage());
            throw new MusicBusinessException(onFail, "qq upstream I/O failed: " + e.getMessage(), e);
        } catch (MusicBusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("QQ upstream parse failed: {}", e.getMessage());
            throw new MusicBusinessException(onFail, "qq upstream parse failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> callJsonp(Request req, MusicErrorCode onFail) {
        try (Response resp = okHttpClient.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            String raw = rb == null ? "" : rb.string();
            if (!resp.isSuccessful()) {
                log.warn("QQ upstream non-2xx status={} body={}", resp.code(), truncate(raw));
                throw new MusicBusinessException(onFail,
                        "qq upstream returned " + resp.code());
            }
            return objectMapper.readValue(stripJsonp(raw), MAP_TYPE);
        } catch (IOException e) {
            log.warn("QQ upstream I/O failed: {}", e.getMessage());
            throw new MusicBusinessException(onFail, "qq upstream I/O failed: " + e.getMessage(), e);
        } catch (MusicBusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("QQ upstream parse failed: {}", e.getMessage());
            throw new MusicBusinessException(onFail, "qq upstream parse failed: " + e.getMessage(), e);
        }
    }

    private boolean isPlaylistAccessible(String disstid) {
        try {
            fetchPlaylistDetailJson(disstid);
            return true;
        } catch (MusicBusinessException e) {
            return false;
        }
    }

    private static String stripJsonp(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int start = s.indexOf('(');
        int end = s.lastIndexOf(')');
        if (start >= 0 && end > start) {
            return s.substring(start + 1, end);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static long asLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (NumberFormatException e) { return 0L; }
        }
        return 0L;
    }

    private static Long asLongObj(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static String unescapeHtml(String s) {
        if (s == null) return null;
        return s.replace("&#160;", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"");
    }

    private static String firstNonEmpty(String... vs) {
        for (String v : vs) if (v != null && !v.isEmpty()) return v;
        return null;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private void ensureQqJsonSuccess(Map<String, Object> json, MusicErrorCode onFail) {
        Integer code = asInt(json.get("code"));
        Integer subcode = asInt(json.get("subcode"));
        if ((code == null || code == 0) && (subcode == null || subcode == 0)) return;

        String message = firstNonEmpty(
                asString(json.get("msg")),
                asString(json.get("message")));
        if (message == null || message.isEmpty()) {
            throw new MusicBusinessException(onFail,
                    "qq upstream business error code=" + code + ", subcode=" + subcode);
        }
        throw new MusicBusinessException(onFail,
                "qq upstream business error code=" + code + ", subcode=" + subcode + ", message=" + message);
    }

    private static String qqPlaylistCategoryId(String category) {
        if (category == null || category.trim().isEmpty()) return "10000000";
        String trimmed = category.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) return "10000000";
        }
        return trimmed;
    }

    private static String qqPlaylistSortId(String order) {
        if (order == null || order.trim().isEmpty()) return "5";
        String trimmed = order.trim().toLowerCase();
        if ("new".equals(trimmed) || "latest".equals(trimmed) || "time".equals(trimmed)) {
            return "2";
        }
        if ("hot".equals(trimmed) || "popular".equals(trimmed)) {
            return "5";
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) return "5";
        }
        return trimmed;
    }
}
