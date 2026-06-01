package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.OkHttpConfig;
import com.example.website.dto.music.LyricInfo;
import com.example.website.dto.music.MusicQuality;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.PlayInfo;
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
import java.util.Map;

/**
 * tf-pay.sayqz.com client for play-URL resolution. Uses the TuneFree token;
 * on failure (incl. probable token expiry), refreshes the token once and
 * retries a single time before surfacing the error.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TuneFreePayClient {

    private static final String PAY_URL = "https://tf-pay.sayqz.com/api/music/";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    private final TuneFreeAuthService auth;
    @Qualifier(OkHttpConfig.CLIENT_MUSIC)
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public PlayInfo play(MusicSource source, String id, MusicQuality quality) {
        String token = auth.getToken();
        PayResult r = call(source, id, quality, token);
        if (r.ok) return toPlayInfo(source, id, quality, r.data0);

        log.info("tf-pay first attempt failed, refreshing token and retrying. reason={}", r.reason);
        token = auth.refresh(true);
        r = call(source, id, quality, token);
        if (r.ok) return toPlayInfo(source, id, quality, r.data0);

        if (r.playUrlMissing) {
            throw new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL,
                    "no playable url for " + source.getValue() + ":" + id);
        }
        throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                "tf-pay failed after retry: " + r.reason);
    }

    private PayResult call(MusicSource source, String id, MusicQuality quality, String token) {
        HttpUrl url = HttpUrl.parse(PAY_URL).newBuilder()
                .addQueryParameter("id", id)
                .addQueryParameter("platform", source.getTfPayPlatform())
                .addQueryParameter("quality", quality.getValue())
                .addQueryParameter("token", token)
                .build();

        Request req = new Request.Builder().url(url).get().build();

        String raw;
        int status;
        try (Response resp = okHttpClient.newCall(req).execute()) {
            status = resp.code();
            ResponseBody rb = resp.body();
            raw = rb == null ? "" : rb.string();
        } catch (IOException e) {
            return PayResult.fail("io: " + e.getMessage(), false);
        }

        if (status < 200 || status >= 300) {
            return PayResult.fail("http " + status + ": " + truncate(raw), false);
        }

        Map<String, Object> json;
        try {
            json = objectMapper.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            return PayResult.fail("non-json: " + truncate(raw), false);
        }

        Map<String, Object> data0 = extractDataZero(json);
        if (data0 == null) {
            return PayResult.fail("no data[0] in response: " + truncate(raw), false);
        }
        Object urlObj = data0.get("url");
        if (!(urlObj instanceof String) || ((String) urlObj).isEmpty()) {
            return PayResult.fail("empty url in data[0]", true);
        }
        return PayResult.ok(data0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractDataZero(Map<String, Object> json) {
        Object outer = json.get("data");
        if (outer instanceof Map) {
            Object inner = ((Map<String, Object>) outer).get("data");
            if (inner instanceof List) {
                List<?> arr = (List<?>) inner;
                if (!arr.isEmpty() && arr.get(0) instanceof Map) {
                    return (Map<String, Object>) arr.get(0);
                }
            }
        }
        if (outer instanceof List) {
            List<?> arr = (List<?>) outer;
            if (!arr.isEmpty() && arr.get(0) instanceof Map) {
                return (Map<String, Object>) arr.get(0);
            }
        }
        return null;
    }

    private PlayInfo toPlayInfo(MusicSource source, String id, MusicQuality quality, Map<String, Object> d) {
        PlayInfo p = new PlayInfo();
        p.setId(id);
        p.setSource(source);
        p.setActualSource(source);
        p.setPlayUrl(asString(d.get("url")));
        p.setRequestedQuality(firstNonEmpty(asString(d.get("requestedQuality")), quality.getValue()));
        p.setActualQuality(asString(d.get("actualQuality")));
        p.setFileSize(asLongObj(d.get("fileSize")));
        p.setExpireSec(asIntObj(d.get("expire")));
        p.setFromCache(asBool(d.get("fromCache")));
        p.setCoverUrl(asString(d.get("cover")));

        Map<String, Object> info = asMap(d.get("info"));
        if (info != null) {
            p.setName(asString(info.get("name")));
            p.setArtist(asString(info.get("artist")));
            p.setAlbum(asString(info.get("album")));
            p.setDurationSec(asIntObj(info.get("duration")));
        }

        String line = asString(d.get("lyrics"));
        String kara = asString(d.get("wordByWordLyrics"));
        if (line != null || kara != null) {
            p.setLyric(new LyricInfo(emptyToNull(line), emptyToNull(kara)));
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static Integer asIntObj(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static Long asLongObj(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static Boolean asBool(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }

    private static String firstNonEmpty(String... vs) {
        for (String v : vs) if (v != null && !v.isEmpty()) return v;
        return null;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static final class PayResult {
        final boolean ok;
        final Map<String, Object> data0;
        final String reason;
        final boolean playUrlMissing;

        private PayResult(boolean ok, Map<String, Object> data0, String reason, boolean playUrlMissing) {
            this.ok = ok;
            this.data0 = data0;
            this.reason = reason;
            this.playUrlMissing = playUrlMissing;
        }

        static PayResult ok(Map<String, Object> data0) {
            return new PayResult(true, data0, null, false);
        }

        static PayResult fail(String reason, boolean playUrlMissing) {
            return new PayResult(false, null, reason, playUrlMissing);
        }
    }
}
