package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.OkHttpConfig;
import com.example.website.dto.music.LyricInfo;
import com.example.website.dto.music.MusicQuality;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.PlayInfo;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static com.example.website.service.music.MusicValueUtils.asInt;
import static com.example.website.service.music.MusicValueUtils.asLongObj;
import static com.example.website.service.music.MusicValueUtils.asMap;
import static com.example.website.service.music.MusicValueUtils.asString;
import static com.example.website.service.music.MusicValueUtils.firstNonEmpty;
import static com.example.website.service.music.MusicValueUtils.truncate;

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
    @Qualifier(OkHttpConfig.CLIENT_MUSIC_THIRD_PARTY)
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final ThirdPartyCircuitBreaker circuitBreaker =
            new ThirdPartyCircuitBreaker("tunefree-pay");
    private final Cache<String, Boolean> recentFailures = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();
    private final Cache<String, Boolean> recentMissingUrls = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    public PlayInfo play(MusicSource source, String id, MusicQuality quality) {
        String requestKey = source.getValue() + ":" + id + ":" + quality.getValue();
        if (recentMissingUrls.getIfPresent(requestKey) != null) {
            throw new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL,
                    "no playable url for " + source.getValue() + ":" + id + " (recently checked)");
        }
        if (recentFailures.getIfPresent(requestKey) != null) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "tunefree-pay recently failed for " + source.getValue() + ":" + id);
        }

        String token = auth.getToken();
        PayResult r = call(source, id, quality, token);
        if (r.ok) {
            recentFailures.invalidate(requestKey);
            recentMissingUrls.invalidate(requestKey);
            return toPlayInfo(source, id, quality, r.data0);
        }

        if (r.authFailure) {
            log.info("tf-pay authentication failed, refreshing token once. reason={}", r.reason);
            token = auth.refresh(true);
            r = call(source, id, quality, token);
            if (r.ok) {
                recentFailures.invalidate(requestKey);
                recentMissingUrls.invalidate(requestKey);
                return toPlayInfo(source, id, quality, r.data0);
            }
        }

        if (r.playUrlMissing) {
            recentMissingUrls.put(requestKey, Boolean.TRUE);
            throw new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL,
                    "no playable url for " + source.getValue() + ":" + id);
        }
        if (r.infrastructureFailure) recentFailures.put(requestKey, Boolean.TRUE);
        throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                "tf-pay failed: " + r.reason);
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
        circuitBreaker.acquire(MusicErrorCode.UPSTREAM_PLAY_FAILED);
        Call call = okHttpClient.newCall(req);
        MusicRequestBudget.constrain(call, 5, TimeUnit.SECONDS);
        try (Response resp = call.execute()) {
            status = resp.code();
            ResponseBody rb = resp.body();
            raw = rb == null ? "" : rb.string();
        } catch (IOException e) {
            circuitBreaker.recordFailure();
            return PayResult.fail("io: " + e.getMessage(), false, false, true);
        }

        if (status < 200 || status >= 300) {
            boolean authFailure = status == 401 || status == 403;
            if (authFailure) circuitBreaker.recordSuccess();
            else circuitBreaker.recordFailure();
            return PayResult.fail("http " + status + ": " + truncate(raw),
                    false, authFailure, !authFailure);
        }

        Map<String, Object> json;
        try {
            json = objectMapper.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            return PayResult.fail("non-json: " + truncate(raw), false, false, true);
        }

        Map<String, Object> data0 = extractDataZero(json);
        if (data0 == null) {
            boolean authFailure = hasAuthenticationHint(raw);
            if (authFailure) circuitBreaker.recordSuccess();
            else circuitBreaker.recordFailure();
            return PayResult.fail("no data[0] in response: " + truncate(raw),
                    false, authFailure, !authFailure);
        }
        Object urlObj = data0.get("url");
        if (!(urlObj instanceof String) || ((String) urlObj).isEmpty()) {
            circuitBreaker.recordSuccess();
            return PayResult.fail("empty url in data[0]", true, false, false);
        }
        circuitBreaker.recordSuccess();
        return PayResult.ok(data0);
    }

    private static boolean hasAuthenticationHint(String raw) {
        if (raw == null) return false;
        String normalized = raw.toLowerCase(Locale.ROOT);
        return normalized.contains("token")
                || normalized.contains("unauthorized")
                || normalized.contains("login")
                || normalized.contains("登录")
                || normalized.contains("过期")
                || normalized.contains("认证");
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
        p.setExpireSec(asInt(d.get("expire")));
        p.setFromCache(asBool(d.get("fromCache")));
        p.setCoverUrl(asString(d.get("cover")));

        Map<String, Object> info = asMap(d.get("info"));
        if (info != null) {
            p.setName(asString(info.get("name")));
            p.setArtist(asString(info.get("artist")));
            p.setAlbum(asString(info.get("album")));
            p.setDurationSec(asInt(info.get("duration")));
        }

        String line = asString(d.get("lyrics"));
        String kara = asString(d.get("wordByWordLyrics"));
        if (line != null || kara != null) {
            p.setLyric(new LyricInfo(emptyToNull(line), emptyToNull(kara)));
        }
        return p;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static Boolean asBool(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }

    private static final class PayResult {
        final boolean ok;
        final Map<String, Object> data0;
        final String reason;
        final boolean playUrlMissing;
        final boolean authFailure;
        final boolean infrastructureFailure;

        private PayResult(boolean ok,
                          Map<String, Object> data0,
                          String reason,
                          boolean playUrlMissing,
                          boolean authFailure,
                          boolean infrastructureFailure) {
            this.ok = ok;
            this.data0 = data0;
            this.reason = reason;
            this.playUrlMissing = playUrlMissing;
            this.authFailure = authFailure;
            this.infrastructureFailure = infrastructureFailure;
        }

        static PayResult ok(Map<String, Object> data0) {
            return new PayResult(true, data0, null, false, false, false);
        }

        static PayResult fail(String reason,
                              boolean playUrlMissing,
                              boolean authFailure,
                              boolean infrastructureFailure) {
            return new PayResult(false, null, reason, playUrlMissing,
                    authFailure, infrastructureFailure);
        }
    }
}
