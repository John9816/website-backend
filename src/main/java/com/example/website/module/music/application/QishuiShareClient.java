package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import com.example.website.config.OkHttpConfig;
import com.example.website.module.music.api.dto.*;
import com.fasterxml.jackson.databind.*;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Public H5 shares only. No caller headers/cookies, automatic redirects or arbitrary hosts. */
@Component
public class QishuiShareClient {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'，。]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID = Pattern.compile("(?:[?&#]|^)playlist_id=([0-9]{6,24})(?=[&#]|$)");
    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final Cache<String, JsonNode> pages = Caffeine.newBuilder().maximumWeight(16 * 1024 * 1024)
            .weigher((String k, JsonNode v) -> v.toString().length() * 2).expireAfterWrite(60, TimeUnit.SECONDS).build();

    public QishuiShareClient(@Qualifier(OkHttpConfig.CLIENT_MUSIC_THIRD_PARTY) OkHttpClient http, ObjectMapper mapper) {
        this.http = http.newBuilder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).callTimeout(5, TimeUnit.SECONDS).build();
        this.mapper = mapper;
    }

    static boolean trusted(HttpUrl u) {
        if (u == null || !u.isHttps() || u.port() != 443 || !u.username().isEmpty() || !u.password().isEmpty()) return false;
        String h = u.host();
        return h.equals("music.douyin.com") || h.equals("qishui.douyin.com") || h.endsWith("-qishui.douyin.com")
                || h.equals("v.douyin.com") || h.equals("qishui.com") || h.endsWith(".qishui.com");
    }

    static boolean isShareHost(String text) {
        Matcher m = URL.matcher(text);
        return m.find() && trusted(HttpUrl.parse(m.group()));
    }

    public String resolve(String text) {
        if (text == null || text.length() > 8192) throw invalid();
        Matcher m = URL.matcher(text);
        if (!m.find()) throw invalid();
        HttpUrl url = HttpUrl.parse(m.group());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        Set<String> visited = new HashSet<>();
        for (int hop = 0; hop < 5; hop++) {
            if (!trusted(url) || !visited.add(url.toString()) || System.nanoTime() > deadline) throw invalid();
            String id = playlistId(url);
            if (id != null) return "share:" + id;
            try (Response r = call(url, deadline).execute()) {
                if (r.isRedirect()) { url = url.resolve(r.header("Location", "")); continue; }
                if (!r.isSuccessful()) throw invalid();
                String html = bounded(r);
                HttpUrl next = null;
                for (Element a : Jsoup.parse(html).select("a[href]")) {
                    HttpUrl candidate = url.resolve(a.attr("href"));
                    if (trusted(candidate) && !visited.contains(candidate.toString())) { next = candidate; break; }
                }
                if (next == null) throw invalid();
                url = next;
            } catch (IOException ex) { throw new BusinessException(502, "汽水分享链接暂时不可用，请稍后重试"); }
        }
        throw invalid();
    }

    private static String playlistId(HttpUrl url) {
        String id = url.queryParameter("playlist_id");
        if (id != null && id.matches("[0-9]{6,24}")) return id;
        String scheme = url.queryParameter("scheme");
        for (int i = 0; scheme != null && i < 3; i++) {
            Matcher m = ID.matcher(scheme);
            if (m.find()) return m.group(1);
            try { String decoded = URLDecoder.decode(scheme, "UTF-8"); if (decoded.equals(scheme)) break; scheme = decoded; }
            catch (Exception ex) { break; }
        }
        return null;
    }

    private Call call(HttpUrl url, long deadline) {
        Call call = http.newCall(new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build());
        call.timeout().timeout(Math.max(1, Math.min(TimeUnit.SECONDS.toNanos(5), deadline - System.nanoTime())), TimeUnit.NANOSECONDS);
        return call;
    }

    private String bounded(Response response) throws IOException {
        if (response.body() == null || response.body().contentLength() > MAX_BYTES) throw invalid();
        response.body().source().request(MAX_BYTES + 1L);
        byte[] bytes = response.body().source().readByteArray(Math.min(MAX_BYTES + 1L, response.body().source().buffer().size()));
        if (bytes.length > MAX_BYTES) throw invalid();
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public PlaylistDetailView fetch(String id, int page, int size) {
        if (id == null || !id.matches("share:[0-9]{6,24}") || page < 1 || page > 1000 || size < 1 || size > 100) throw invalid();
        JsonNode node = pages.get(id, key -> {
            HttpUrl url = HttpUrl.parse("https://music.douyin.com/qishui/share/playlist?playlist_id=" + key.substring(6));
            try (Response r = call(url, System.nanoTime() + TimeUnit.SECONDS.toNanos(5)).execute()) {
                if (!r.isSuccessful()) throw invalid();
                return router(bounded(r));
            } catch (IOException ex) { throw new BusinessException(502, "汽水分享歌单暂时不可用"); }
        });
        PlaylistDetailView all = view(id, node);
        int from = Math.min(all.getList().size(), (page - 1) * size), to = Math.min(all.getList().size(), from + size);
        all.setList(new ArrayList<>(all.getList().subList(from, to)));
        all.setPage(page); all.setPageSize(size);
        return all;
    }

    PlaylistDetailView parse(String id, String html) { return view(id, router(html)); }

    private JsonNode router(String html) {
        if (html.length() > MAX_BYTES) throw invalid();
        int marker = html.indexOf("_ROUTER_DATA"), start = marker < 0 ? -1 : html.indexOf('{', marker);
        if (start < 0) throw invalid();
        boolean quoted = false, escaped = false; int depth = 0;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quoted) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') quoted = false; }
            else if (c == '"') quoted = true;
            else if (c == '{') { if (++depth > 64) throw invalid(); }
            else if (c == '}' && --depth == 0) {
                try {
                    JsonNode page = mapper.readTree(html.substring(start, i + 1)).path("loaderData").path("playlist_page");
                    if (!page.path("playlistInfo").isObject() || !page.path("medias").isArray()) throw invalid();
                    return page;
                } catch (IOException ex) { throw invalid(); }
            }
        }
        throw invalid();
    }

    private PlaylistDetailView view(String id, JsonNode page) {
        JsonNode info = page.path("playlistInfo");
        if (!id.substring(6).equals(info.path("id").asText())) throw invalid();
        Map<String, SongSearchItem> songs = new LinkedHashMap<>();
        for (JsonNode media : page.path("medias")) {
            if (!"track".equals(media.path("type").asText())) continue;
            JsonNode t = media.path("entity").path("track");
            String trackId = t.path("id").asText("");
            if (!trackId.matches("[0-9]{1,24}") || t.path("name").asText("").isEmpty()) continue;
            SongSearchItem song = new SongSearchItem();song.setId(trackId);song.setSource(MusicSource.QISHUI);song.setName(t.path("name").asText());
            List<String> names = new ArrayList<>(), ids = new ArrayList<>();
            for (JsonNode a : t.path("artists")) { names.add(a.path("name").asText()); ids.add(a.path("id").asText()); }
            song.setArtist(String.join(" / ", names));song.setArtistIds(ids);song.setArtistId(ids.isEmpty() ? null : ids.get(0));
            song.setAlbum(t.path("album").path("name").asText(null));song.setAlbumId(t.path("album").path("id").asText(null));
            song.setCoverUrl(QishuiMusicClient.imageUrl(t.path("album").path("url_cover")));
            song.setDurationMs(t.path("duration").asLong(0));song.setDurationSec((int)(song.getDurationMs()/1000));
            song.setAvailableQualities(Collections.emptyList());songs.putIfAbsent(trackId, song);
        }
        if (songs.isEmpty() && info.path("count_tracks").asInt(0) > 0) throw new BusinessException(502, "分享页面暂未提供可导入的歌曲");
        String note = "公开分享页可用音轨 " + songs.size() + " 首（页面标称 " + info.path("count_tracks").asInt(songs.size()) + " 首）；不含视频及未公开音轨。";
        return new PlaylistDetailView(id, MusicSource.QISHUI, info.path("title").asText(info.path("public_title").asText("汽水歌单")),
                QishuiMusicClient.imageUrl(info.path("url_cover")), info.path("desc").asText("") + "\n" + note,
                info.path("owner").path("nickname").asText(null), info.path("stats").path("count_played").asLong(0), null,
                1, songs.size(), songs.size(), new ArrayList<>(songs.values()));
    }
    private static BusinessException invalid() { return new BusinessException(400, "请提供有效的汽水官方歌单分享链接"); }
}
