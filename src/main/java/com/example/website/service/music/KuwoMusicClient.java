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
import com.fasterxml.jackson.core.JsonParser;
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

/**
 * Kuwo search client. Note: the endpoint is HTTP, not HTTPS — this is by
 * design, the upstream does not offer an HTTPS variant that accepts the
 * {@code client=kt} mobile query. The spec warns that browsers would hit
 * mixed-content blocks if they tried to call it directly; we proxy it here
 * so the frontend never sees the HTTP URL.
 *
 * <p>No native lyric endpoint is known to be stable; {@link MusicService}
 * routes kuwo lyric requests through tf-pay.play instead of calling here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KuwoMusicClient {

    private static final String SEARCH_URL = "http://search.kuwo.cn/r.s";
    private static final String MUSIC_INFO_URL = "http://www.kuwo.cn/api/www/music/musicInfo";
    private static final String TOPLIST_TREE_URL = "http://qukudata.kuwo.cn/q.k";
    private static final String TOPLIST_DETAIL_URL = "http://kbangserver.kuwo.cn/ksong.s";
    private static final String PLAYLIST_LIST_URL = "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList";
    private static final String PLAYLIST_DETAIL_URL = "http://nplserver.kuwo.cn/pl.svc";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    @Qualifier(OkHttpConfig.CLIENT_MUSIC)
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public List<SongSearchItem> search(String keyword, int page, int pageSize) {
        int pn = Math.max(0, page - 1);
        HttpUrl url = HttpUrl.parse(SEARCH_URL).newBuilder()
                .addQueryParameter("client", "kt")
                .addQueryParameter("all", keyword)
                .addQueryParameter("pn", String.valueOf(pn))
                .addQueryParameter("rn", String.valueOf(pageSize))
                .addQueryParameter("ft", "music")
                .addQueryParameter("encoding", "utf8")
                .addQueryParameter("rformat", "json")
                .addQueryParameter("vipver", "MUSIC_9.0.5.0_W1")
                .addQueryParameter("newver", "1")
                .build();

        Request req = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build();

        String raw;
        try (Response resp = okHttpClient.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            raw = rb == null ? "" : rb.string();
            if (!resp.isSuccessful()) {
                log.warn("Kuwo upstream non-2xx status={} body={}", resp.code(), truncate(raw));
                throw new MusicBusinessException(MusicErrorCode.UPSTREAM_SEARCH_FAILED,
                        "kuwo upstream returned " + resp.code());
            }
        } catch (IOException e) {
            log.warn("Kuwo upstream I/O failed: {}", e.getMessage());
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_SEARCH_FAILED,
                    "kuwo upstream I/O failed: " + e.getMessage(), e);
        }

        Map<String, Object> json;
        try {
            json = parseSearchPayload(raw);
        } catch (Exception e) {
            log.warn("Kuwo upstream parse failed: {}", truncate(raw));
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_SEARCH_FAILED,
                    "kuwo upstream parse failed: " + e.getMessage(), e);
        }
        return parseSearch(json);
    }

    public NeteaseMusicClient.CollectionSearchResult searchCollections(String keyword, MusicSearchType type, int page, int pageSize) {
        if (type == null || type == MusicSearchType.SONG) return new NeteaseMusicClient.CollectionSearchResult(0L, Collections.emptyList());
        int pn = Math.max(0, page - 1);
        HttpUrl url = HttpUrl.parse(SEARCH_URL).newBuilder()
                .addQueryParameter("client", "kt").addQueryParameter("all", keyword)
                .addQueryParameter("pn", String.valueOf(pn)).addQueryParameter("rn", String.valueOf(pageSize))
                .addQueryParameter("ft", type == MusicSearchType.PLAYLIST ? "playlist" : type.getValue())
                .addQueryParameter("encoding", "utf8").addQueryParameter("rformat", "json")
                .addQueryParameter("vipver", "MUSIC_9.0.5.0_W1").addQueryParameter("newver", "1").build();
        Request req = new Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "Mozilla/5.0").get().build();
        try (Response resp = okHttpClient.newCall(req).execute()) {
            String raw = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) throw new MusicBusinessException(MusicErrorCode.UPSTREAM_SEARCH_FAILED, "kuwo upstream returned " + resp.code());
            Map<String, Object> json = parseSearchPayload(raw);
            return parseCollectionSearch(json, type);
        } catch (IOException e) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_SEARCH_FAILED, "kuwo collection search failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private NeteaseMusicClient.CollectionSearchResult parseCollectionSearch(Map<String, Object> json, MusicSearchType type) {
        Object rows = json.get("abslist");
        if (!(rows instanceof List)) rows = json.get("albumlist");
        List<SearchCollectionItem> out = new ArrayList<>();
        if (rows instanceof List) for (Object row : (List<?>) rows) {
            if (!(row instanceof Map)) continue;
            Map<String, Object> s = (Map<String, Object>) row;
            SearchCollectionItem item = new SearchCollectionItem();
            item.setSource(MusicSource.KUWO); item.setType(type);
            if (type == MusicSearchType.ARTIST) {
                item.setId(firstNonEmpty(asString(s.get("ARTISTID")), asString(s.get("artistid")), asString(s.get("DC_TARGETID")), asString(s.get("id"))));
                item.setName(firstNonEmpty(asString(s.get("ARTIST")), asString(s.get("name"))));
                item.setCoverUrl(firstNonEmpty(asString(s.get("hts_PICPATH")), asString(s.get("PICPATH")), asString(s.get("artistpic"))));
                item.setTrackCount(asInt(firstNonEmpty(asString(s.get("SONGNUM")), asString(s.get("songnum")))));
            } else if (type == MusicSearchType.ALBUM) {
                item.setId(firstNonEmpty(asString(s.get("albumid")), asString(s.get("id")), asString(s.get("DC_TARGETID"))));
                item.setName(firstNonEmpty(asString(s.get("name")), asString(s.get("album")), asString(s.get("ALBUM"))));
                item.setArtist(firstNonEmpty(asString(s.get("artist")), asString(s.get("ARTIST")), asString(s.get("fartist"))));
                item.setCoverUrl(firstNonEmpty(asString(s.get("hts_img")), asString(s.get("img")), asString(s.get("pic"))));
                item.setTrackCount(asInt(firstNonEmpty(asString(s.get("musiccnt")), asString(s.get("songnum")))));
                item.setPlayCount(asLongObj(firstNonEmpty(asString(s.get("PLAYCNT")), asString(s.get("playcnt")))));
            } else {
                item.setId(firstNonEmpty(asString(s.get("playlistid")), asString(s.get("id")), asString(s.get("DC_TARGETID"))));
                item.setName(firstNonEmpty(asString(s.get("name")), asString(s.get("title"))));
                item.setCreatorName(firstNonEmpty(asString(s.get("nickname")), asString(s.get("uname"))));
                item.setCoverUrl(firstNonEmpty(asString(s.get("hts_pic")), asString(s.get("pic")), asString(s.get("img"))));
                item.setTrackCount(asInt(firstNonEmpty(asString(s.get("songnum")), asString(s.get("total")))));
                item.setPlayCount(asLongObj(firstNonEmpty(asString(s.get("playcnt")), asString(s.get("listencnt")))));
            }
            if (item.getId() != null && !item.getId().isEmpty() && item.getName() != null && !item.getName().isEmpty()) out.add(item);
        }
        Long total = asLongObj(firstNonEmpty(asString(json.get("TOTAL")), asString(json.get("total")), asString(json.get("HIT"))));
        return new NeteaseMusicClient.CollectionSearchResult(total == null ? (long) out.size() : total, out);
    }

    private Map<String, Object> parseSearchPayload(String raw) throws IOException {
        try {
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (Exception ignored) {
            ObjectMapper relaxed = objectMapper.copy();
            relaxed.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
            return relaxed.readValue(raw, MAP_TYPE);
        }
    }

    /**
     * Best-effort song metadata lookup by id. Kuwo's public musicInfo
     * endpoint often requires a csrf cookie / secret header that we don't
     * have, so this may return null. Callers must treat null as "unable to
     * reverse-lookup; no fallback possible from kuwo as primary".
     */
    @SuppressWarnings("unchecked")
    public SongSearchItem fetchSongInfo(String id) {
        HttpUrl url = HttpUrl.parse(MUSIC_INFO_URL).newBuilder()
                .addQueryParameter("mid", id)
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", "http://www.kuwo.cn/")
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .header("Cookie", "kw_token=BACKEND")
                .header("csrf", "BACKEND")
                .get()
                .build();

        String raw;
        try (Response resp = okHttpClient.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            raw = rb == null ? "" : rb.string();
            if (!resp.isSuccessful()) {
                log.debug("Kuwo songInfo non-2xx for id={}: status={}", id, resp.code());
                return null;
            }
        } catch (IOException e) {
            log.debug("Kuwo songInfo I/O failed for id={}: {}", id, e.getMessage());
            return null;
        }

        Map<String, Object> json;
        try {
            json = objectMapper.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            return null;
        }
        Object d = json.get("data");
        if (!(d instanceof Map)) return null;
        Map<String, Object> data = (Map<String, Object>) d;
        String name = asString(data.get("name"));
        String artist = asString(data.get("artist"));
        if (name == null || name.isEmpty() || artist == null || artist.isEmpty()) {
            return null;
        }

        SongSearchItem v = new SongSearchItem();
        v.setSource(MusicSource.KUWO);
        v.setId(id);
        v.setName(name);
        v.setArtist(artist);
        v.setAlbum(asString(data.get("album")));
        return v;
    }

    /**
     * Kuwo toplist list. The upstream is a tree endpoint — each node has a
     * {@code child} array; leaf entries (actual charts) carry a
     * {@code sourceid} field that the detail endpoint consumes as {@code id}.
     * We recurse and flatten all sourceid-bearing nodes.
     */
    public List<ToplistItem> fetchToplists() {
        HttpUrl url = HttpUrl.parse(TOPLIST_TREE_URL).newBuilder()
                .addQueryParameter("op", "query")
                .addQueryParameter("cont", "tree")
                .addQueryParameter("node", "2")
                .addQueryParameter("pn", "0")
                .addQueryParameter("rn", "1000")
                .addQueryParameter("fmt", "json")
                .addQueryParameter("level", "2")
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build();
        Map<String, Object> json = callJson(req, MusicErrorCode.UPSTREAM_TOPLIST_FAILED);
        List<ToplistItem> out = new ArrayList<>();
        walkTree(json, out);
        return out;
    }

    /**
     * Kuwo toplist detail. {@code kbangserver} supports native {@code pn/rn}
     * pagination (pn is 0-based). For "酷我新歌榜" pass {@code id="17"}.
     */
    public ToplistDetailView fetchToplistDetail(String id, int page, int pageSize) {
        int pn = Math.max(0, page - 1);
        HttpUrl url = HttpUrl.parse(TOPLIST_DETAIL_URL).newBuilder()
                .addQueryParameter("from", "pc")
                .addQueryParameter("fmt", "json")
                .addQueryParameter("pn", String.valueOf(pn))
                .addQueryParameter("rn", String.valueOf(pageSize))
                .addQueryParameter("type", "bang")
                .addQueryParameter("data", "content")
                .addQueryParameter("id", id)
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build();
        Map<String, Object> json = callJson(req, MusicErrorCode.UPSTREAM_TOPLIST_FAILED);
        ToplistDetailView view = parseToplistDetail(json, id, page, pageSize);
        fillToplistMetaFromList(view);
        return view;
    }

    public PlaylistListView fetchPlaylists(int page, int pageSize) {
        HttpUrl url = HttpUrl.parse(PLAYLIST_LIST_URL).newBuilder()
                .addQueryParameter("loginUid", "0")
                .addQueryParameter("loginSid", "0")
                .addQueryParameter("appUid", "76039576")
                .addQueryParameter("pn", String.valueOf(page))
                .addQueryParameter("rn", String.valueOf(pageSize))
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build();
        Map<String, Object> json = callJson(req, MusicErrorCode.UPSTREAM_PLAYLIST_FAILED);
        return parsePlaylistList(json, page, pageSize);
    }

    public PlaylistDetailView fetchPlaylistDetail(String id, int page, int pageSize) {
        int pn = Math.max(0, page - 1);
        HttpUrl url = HttpUrl.parse(PLAYLIST_DETAIL_URL).newBuilder()
                .addQueryParameter("op", "getlistinfo")
                .addQueryParameter("pid", id)
                .addQueryParameter("pn", String.valueOf(pn))
                .addQueryParameter("rn", String.valueOf(pageSize))
                .addQueryParameter("encode", "utf8")
                .addQueryParameter("keyset", "pl2012")
                .addQueryParameter("vipver", "MUSIC_9.1.1.2_BCS2")
                .addQueryParameter("newver", "1")
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build();
        Map<String, Object> json = callJson(req, MusicErrorCode.UPSTREAM_PLAYLIST_FAILED);
        return parsePlaylistDetail(json, id, page, pageSize);
    }

    @SuppressWarnings("unchecked")
    private void walkTree(Map<String, Object> node, List<ToplistItem> out) {
        String sourceid = asString(node.get("sourceid"));
        if (sourceid != null && !sourceid.isEmpty()) {
            ToplistItem t = new ToplistItem();
            t.setSource(MusicSource.KUWO);
            t.setId(sourceid);
            t.setName(firstNonEmpty(asString(node.get("name")), asString(node.get("disname"))));
            t.setCoverUrl(firstNonEmpty(
                    asString(node.get("pic")),
                    asString(node.get("picurl")),
                    asString(node.get("img"))));
            t.setDescription(asString(node.get("info")));
            t.setUpdateTime(asString(node.get("pub")));
            out.add(t);
        }
        Object children = node.get("child");
        if (!(children instanceof List)) return;
        for (Object c : (List<?>) children) {
            if (c instanceof Map) walkTree((Map<String, Object>) c, out);
        }
    }

    @SuppressWarnings("unchecked")
    private PlaylistListView parsePlaylistList(Map<String, Object> json, int page, int pageSize) {
        PlaylistListView v = new PlaylistListView();
        v.setSource(MusicSource.KUWO);
        v.setPage(page);
        v.setPageSize(pageSize);
        v.setList(Collections.emptyList());

        Object dataObj = json.get("data");
        if (!(dataObj instanceof Map)) return v;
        Map<String, Object> data = (Map<String, Object>) dataObj;
        Long total = asLongObj(data.get("total"));
        if (total != null) v.setTotal(total);

        Object listObj = data.get("data");
        if (!(listObj instanceof List)) return v;

        List<PlaylistItem> out = new ArrayList<>();
        for (Object item : (List<?>) listObj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> s = (Map<String, Object>) item;
            PlaylistItem p = new PlaylistItem();
            p.setSource(MusicSource.KUWO);
            p.setId(asString(s.get("id")));
            p.setName(asString(s.get("name")));
            p.setCoverUrl(firstNonEmpty(asString(s.get("img")), asString(s.get("pic"))));
            p.setDescription(firstNonEmpty(asString(s.get("info")), asString(s.get("desc"))));
            p.setCreatorName(asString(s.get("uname")));
            p.setTrackCount(asInt(s.get("total")));
            p.setPlayCount(asLongObj(s.get("listencnt")));
            if (p.getId() != null && !p.getId().isEmpty()) out.add(p);
        }
        v.setList(out);
        return v;
    }

    @SuppressWarnings("unchecked")
    private ToplistDetailView parseToplistDetail(Map<String, Object> json, String reqId, int page, int pageSize) {
        ToplistDetailView v = new ToplistDetailView();
        v.setId(reqId);
        v.setSource(MusicSource.KUWO);
        v.setPage(page);
        v.setPageSize(pageSize);
        v.setList(Collections.emptyList());

        v.setName(asString(json.get("name")));
        v.setCoverUrl(firstNonEmpty(
                asString(json.get("pic")),
                asString(json.get("picurl")),
                asString(json.get("img"))));
        v.setDescription(asString(json.get("info")));
        v.setUpdateTime(asString(json.get("pub")));
        Integer total = asInt(json.get("total"));
        if (total == null) total = asInt(json.get("num"));
        v.setTotal(total);

        Object ml = json.get("musiclist");
        if (!(ml instanceof List)) return v;

        List<SongSearchItem> out = new ArrayList<>();
        for (Object item : (List<?>) ml) {
            if (!(item instanceof Map)) continue;
            SongSearchItem si = toSongItem((Map<String, Object>) item);
            if (si.getId() != null && !si.getId().isEmpty()) out.add(si);
        }
        v.setList(out);
        return v;
    }

    private void fillToplistMetaFromList(ToplistDetailView view) {
        if (view == null) return;
        if (!isEmpty(view.getCoverUrl())
                && !isEmpty(view.getName())
                && !isEmpty(view.getDescription())
                && !isEmpty(view.getUpdateTime())) {
            return;
        }

        try {
            for (ToplistItem item : fetchToplists()) {
                if (!view.getId().equals(item.getId())) continue;
                if (isEmpty(view.getName())) view.setName(item.getName());
                if (isEmpty(view.getCoverUrl())) view.setCoverUrl(item.getCoverUrl());
                if (isEmpty(view.getDescription())) view.setDescription(item.getDescription());
                if (isEmpty(view.getUpdateTime())) view.setUpdateTime(item.getUpdateTime());
                return;
            }
        } catch (MusicBusinessException ex) {
            log.debug("Kuwo toplist meta fallback failed for id={}: {}", view.getId(), ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private PlaylistDetailView parsePlaylistDetail(Map<String, Object> json, String reqId, int page, int pageSize) {
        PlaylistDetailView v = new PlaylistDetailView();
        v.setId(reqId);
        v.setSource(MusicSource.KUWO);
        v.setPage(page);
        v.setPageSize(pageSize);
        v.setList(Collections.emptyList());

        String playlistId = asString(json.get("id"));
        if (playlistId != null && !playlistId.isEmpty()) v.setId(playlistId);
        v.setName(firstNonEmpty(asString(json.get("title")), asString(json.get("name"))));
        v.setCoverUrl(firstNonEmpty(asString(json.get("pic")), asString(json.get("img"))));
        v.setDescription(firstNonEmpty(asString(json.get("info")), asString(json.get("desc"))));
        v.setCreatorName(asString(json.get("uname")));
        v.setPlayCount(asLongObj(json.get("playnum")));
        Object updateTime = json.get("abstime");
        if (updateTime != null) v.setUpdateTime(asString(updateTime));
        Integer total = asInt(json.get("total"));
        if (total == null) total = asInt(json.get("validtotal"));
        v.setTotal(total);

        Object ml = json.get("musiclist");
        if (!(ml instanceof List)) return v;

        List<SongSearchItem> out = new ArrayList<>();
        for (Object item : (List<?>) ml) {
            if (!(item instanceof Map)) continue;
            SongSearchItem si = toSongItem((Map<String, Object>) item);
            if (si.getId() != null && !si.getId().isEmpty()) out.add(si);
        }
        v.setList(out);
        return v;
    }

    private Map<String, Object> callJson(Request req, MusicErrorCode onFail) {
        String raw;
        try (Response resp = okHttpClient.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            raw = rb == null ? "" : rb.string();
            if (!resp.isSuccessful()) {
                log.warn("Kuwo upstream non-2xx status={} body={}", resp.code(), truncate(raw));
                throw new MusicBusinessException(onFail, "kuwo upstream returned " + resp.code());
            }
        } catch (IOException e) {
            log.warn("Kuwo upstream I/O failed: {}", e.getMessage());
            throw new MusicBusinessException(onFail, "kuwo upstream I/O failed: " + e.getMessage(), e);
        }
        try {
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Kuwo upstream parse failed: {}", truncate(raw));
            throw new MusicBusinessException(onFail, "kuwo upstream parse failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<SongSearchItem> parseSearch(Map<String, Object> json) {
        Object abslistObj = json.get("abslist");
        if (!(abslistObj instanceof List)) return Collections.emptyList();

        List<SongSearchItem> out = new ArrayList<>();
        for (Object item : (List<?>) abslistObj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> s = (Map<String, Object>) item;
            SongSearchItem v = new SongSearchItem();
            v.setSource(MusicSource.KUWO);
            v.setId(firstNonEmpty(asString(s.get("DC_TARGETID")), asString(s.get("MUSICRID"))));
            v.setName(firstNonEmpty(asString(s.get("NAME")), asString(s.get("SONGNAME"))));
            v.setArtist(asString(s.get("ARTIST")));
            v.setAlbum(asString(s.get("ALBUM")));
            v.setCoverUrl(kuwoCoverUrl(s));
            Integer durSec = asInt(s.get("DURATION"));
            if (durSec != null) {
                v.setDurationSec(durSec);
                v.setDurationMs((long) durSec * 1000L);
            }
            v.setAvailableQualities(qualitiesFromNminfo(asString(s.get("N_MINFO"))));
            if (v.getId() != null && !v.getId().isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private SongSearchItem toSongItem(Map<String, Object> s) {
        SongSearchItem si = new SongSearchItem();
        si.setSource(MusicSource.KUWO);
        si.setId(firstNonEmpty(
                asString(s.get("id")),
                asString(s.get("musicrid")),
                asString(s.get("MUSICRID"))));
        si.setName(firstNonEmpty(asString(s.get("name")), asString(s.get("songname"))));
        si.setArtist(firstNonEmpty(asString(s.get("artist")), asString(s.get("ARTIST")), asString(s.get("FARTIST"))));
        si.setAlbum(firstNonEmpty(asString(s.get("album")), asString(s.get("ALBUM")), asString(s.get("FALBUM"))));
        si.setCoverUrl(kuwoCoverUrl(s));
        Integer dur = asInt(s.get("duration"));
        if (dur == null) dur = asInt(s.get("DURATION"));
        if (dur != null) {
            si.setDurationSec(dur);
            si.setDurationMs((long) dur * 1000L);
        }
        si.setAvailableQualities(qualitiesFromNminfo(firstNonEmpty(asString(s.get("N_MINFO")), asString(s.get("MINFO")))));
        return si;
    }

    /**
     * {@code N_MINFO} is a semicolon-separated list like
     * {@code bitrate:128,format:mp3,size:...;bitrate:320,format:mp3,size:...}.
     * We only check for substring hints — the authoritative quality is
     * {@code actualQuality} from /play, per spec §6.4.
     */
    private List<String> qualitiesFromNminfo(String nminfo) {
        List<String> q = new ArrayList<>();
        if (nminfo == null || nminfo.isEmpty()) return q;
        String lower = nminfo.toLowerCase();
        if (lower.contains("bitrate:128") || lower.contains("128kmp3") || lower.contains("128k"))  q.add("128k");
        if (lower.contains("bitrate:320") || lower.contains("320kmp3") || lower.contains("320k"))  q.add("320k");
        if (lower.contains("flac"))                                                                q.add("flac");
        return q;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt(((String) v).trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static Long asLongObj(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong(((String) v).trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static String firstNonEmpty(String... vs) {
        for (String v : vs) if (v != null && !v.isEmpty()) return v;
        return null;
    }

    private static String kuwoCoverUrl(Map<String, Object> s) {
        return firstNonEmpty(
                asString(s.get("albumpic")),
                asString(s.get("pic")),
                asString(s.get("hts_MVPIC")),
                absoluteKuwoImage(asString(s.get("MVPIC")), "wmvpic"),
                absoluteKuwoImage(asString(s.get("web_albumpic_short")), "star/albumcover"),
                absoluteKuwoImage(asString(s.get("web_artistpic_short")), "star/starheads"),
                absoluteKuwoImage(asString(s.get("PICPATH")), "star/starheads"));
    }

    private static String absoluteKuwoImage(String path, String basePath) {
        if (path == null || path.isEmpty()) return null;
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        String normalizedPath = path.replaceAll("^/+", "");
        return "https://img1.kuwo.cn/" + basePath + "/" + normalizedPath;
    }

    private static boolean isEmpty(String v) {
        return v == null || v.isEmpty();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
