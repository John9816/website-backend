package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.OkHttpConfig;
import com.example.website.dto.music.LyricInfo;
import com.example.website.dto.music.MusicQuality;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.PlayInfo;
import com.example.website.dto.music.SongSearchItem;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class QqTextFallbackClient {

    private static final String API_URL = "https://cyapi.top/API/qq_music.php";
    private static final String API_KEY = "62ccfd8be755cc5850046044c6348d6cac5ef31bd5874c1352287facc06f94c4";
    private static final String REFERER = "https://cyapi.top/";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    @Qualifier(OkHttpConfig.CLIENT_MUSIC)
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public PlayInfo fetchSongUrl(String requestedId, MusicQuality requestedQuality, SongSearchItem metadata) {
        ResolvedTrack track = resolve(metadata);
        if (isEmpty(track.playUrl)) {
            throw new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL,
                    "qq text fallback returned no playable url for " + requestedId);
        }

        PlayInfo info = new PlayInfo();
        info.setId(requestedId);
        info.setSource(MusicSource.QQ);
        info.setActualSource(MusicSource.QQ);
        info.setName(track.name);
        info.setArtist(track.artist);
        info.setAlbum(track.album);
        info.setCoverUrl(track.coverUrl);
        info.setDurationSec(track.durationSec);
        info.setPlayUrl(track.playUrl);
        info.setActualQuality(firstNonEmpty(track.actualQuality, requestedQuality == null ? null : requestedQuality.getValue()));
        if (!isEmpty(track.lineLyrics)) {
            info.setLyric(new LyricInfo(track.lineLyrics, null));
        }
        return info;
    }

    public LyricInfo fetchLyric(SongSearchItem metadata) {
        ResolvedTrack track = resolve(metadata);
        if (isEmpty(track.lineLyrics)) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_LYRIC_FAILED,
                    "qq text fallback returned no lyric for keyword=" + buildKeyword(metadata));
        }
        return new LyricInfo(track.lineLyrics, null);
    }

    private ResolvedTrack resolve(SongSearchItem metadata) {
        String keyword = buildKeyword(metadata);
        HttpUrl url = HttpUrl.parse(API_URL).newBuilder()
                .addQueryParameter("apikey", API_KEY)
                .addQueryParameter("type", "json")
                .addQueryParameter("n", "1")
                .addQueryParameter("msg", keyword)
                .build();

        Request req = new Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .get()
                .build();

        Map<String, Object> json = callJson(req);
        ResolvedTrack track = new ResolvedTrack();
        Map<String, Object> data = asMap(json.get("data"));

        track.name = firstNonEmpty(
                readString(data, "name"),
                readString(data, "title"),
                readString(json, "name"),
                readString(json, "title"),
                metadata == null ? null : metadata.getName());
        track.artist = firstNonEmpty(
                readArtists(data),
                readArtists(json),
                readString(data, "artist"),
                readString(data, "singer"),
                readString(json, "artist"),
                readString(json, "singer"),
                metadata == null ? null : metadata.getArtist());
        track.album = firstNonEmpty(
                readAlbum(data),
                readAlbum(json),
                metadata == null ? null : metadata.getAlbum());
        track.coverUrl = firstNonEmpty(
                readCover(data),
                readCover(json),
                metadata == null ? null : metadata.getCoverUrl());
        track.durationSec = firstNonNull(
                readInt(data, "duration"),
                readInt(data, "interval"),
                readInt(json, "duration"),
                readInt(json, "interval"),
                metadata == null ? null : metadata.getDurationSec());
        track.actualQuality = firstNonEmpty(readQuality(data), readQuality(json));
        track.playUrl = firstNonEmpty(
                readNestedString(data, "music_url"),
                readNestedString(data, "url"),
                readNestedString(data, "song_url"),
                readNestedString(data, "mp3"),
                readString(json, "music_url"),
                readString(json, "url"),
                readString(json, "song_url"),
                readString(json, "mp3"));
        track.lineLyrics = firstNonEmpty(
                readLyric(data),
                readLyric(json));
        return track;
    }

    private Map<String, Object> callJson(Request req) {
        try (Response resp = okHttpClient.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            String raw = rb == null ? "" : rb.string();
            if (!resp.isSuccessful()) {
                log.warn("QQ text fallback non-2xx status={} body={}", resp.code(), truncate(raw));
                throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                        "qq text fallback returned " + resp.code());
            }
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (IOException e) {
            log.warn("QQ text fallback I/O failed: {}", e.getMessage());
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "qq text fallback I/O failed: " + e.getMessage(), e);
        } catch (MusicBusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("QQ text fallback parse failed: {}", e.getMessage());
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "qq text fallback parse failed: " + e.getMessage(), e);
        }
    }

    private static String buildKeyword(SongSearchItem metadata) {
        if (metadata == null || isEmpty(metadata.getName())) {
            throw new MusicBusinessException(MusicErrorCode.SONG_NOT_FOUND,
                    "qq text fallback requires song metadata");
        }
        if (isEmpty(metadata.getArtist())) {
            return metadata.getName().trim();
        }
        return metadata.getName().trim() + " " + metadata.getArtist().trim();
    }

    private static String readQuality(Map<String, Object> root) {
        if (root == null) return null;
        Map<String, Object> quality = asMap(root.get("quality"));
        if (quality == null) return null;
        String current = readString(quality, "current");
        if (isEmpty(current)) return null;
        String normalized = current.trim().toLowerCase(Locale.ROOT);
        if ("standard".equals(normalized)) return MusicQuality.K128.getValue();
        if ("high".equals(normalized)) return MusicQuality.K320.getValue();
        if ("lossless".equals(normalized)) return MusicQuality.FLAC.getValue();
        if ("master".equals(normalized) || "hires".equals(normalized) || "flac24bit".equals(normalized)) {
            return MusicQuality.FLAC24.getValue();
        }
        return current;
    }

    private static String readCover(Map<String, Object> root) {
        if (root == null) return null;
        Map<String, Object> cover = asMap(root.get("cover"));
        if (cover != null) {
            String hit = firstNonEmpty(
                    readString(cover, "large"),
                    readString(cover, "medium"),
                    readString(cover, "small"),
                    readString(cover, "url"));
            if (!isEmpty(hit)) return hit;
        }
        return firstNonEmpty(
                readString(root, "coverUrl"),
                readString(root, "cover"),
                readString(root, "pic"),
                readString(root, "picurl"),
                readString(root, "img"));
    }

    private static String readAlbum(Map<String, Object> root) {
        if (root == null) return null;
        Map<String, Object> album = asMap(root.get("album"));
        if (album != null) {
            String hit = firstNonEmpty(readString(album, "name"), readString(album, "title"));
            if (!isEmpty(hit)) return hit;
        }
        return readString(root, "album");
    }

    private static String readArtists(Map<String, Object> root) {
        if (root == null) return null;
        Object artistsObj = root.get("artists");
        if (artistsObj instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object artist : (List<?>) artistsObj) {
                Map<String, Object> map = asMap(artist);
                String name = map == null ? asString(artist) : readString(map, "name");
                if (isEmpty(name)) continue;
                if (sb.length() > 0) sb.append(" / ");
                sb.append(name);
            }
            if (sb.length() > 0) return sb.toString();
        }
        return firstNonEmpty(
                readString(root, "artists"),
                readString(root, "author"));
    }

    private static String readLyric(Map<String, Object> root) {
        if (root == null) return null;
        String direct = firstNonEmpty(
                readString(root, "lyric"),
                readString(root, "lrc"));
        if (!isEmpty(direct)) return direct;

        Object lyricObj = root.get("lyric");
        Map<String, Object> lyricMap = asMap(lyricObj);
        if (lyricMap != null) {
            return firstNonEmpty(
                    readString(lyricMap, "text"),
                    readString(lyricMap, "lyric"),
                    readString(lyricMap, "lrc"),
                    readString(lyricMap, "content"));
        }

        Object lrcObj = root.get("lrc");
        Map<String, Object> lrcMap = asMap(lrcObj);
        if (lrcMap != null) {
            return firstNonEmpty(
                    readString(lrcMap, "text"),
                    readString(lrcMap, "lyric"),
                    readString(lrcMap, "lrc"),
                    readString(lrcMap, "content"));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static String readNestedString(Map<String, Object> root, String key) {
        return root == null ? null : readString(root, key);
    }

    private static String readString(Map<String, Object> root, String key) {
        if (root == null) return null;
        Object v = root.get(key);
        if (v instanceof Map || v instanceof List) return null;
        return asString(v);
    }

    private static Integer readInt(Map<String, Object> root, String key) {
        if (root == null) return null;
        Object v = root.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt((String) v);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Integer firstNonNull(Integer... values) {
        for (Integer value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!isEmpty(value)) return value;
        }
        return null;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static final class ResolvedTrack {
        private String name;
        private String artist;
        private String album;
        private String coverUrl;
        private Integer durationSec;
        private String playUrl;
        private String actualQuality;
        private String lineLyrics;
    }
}
