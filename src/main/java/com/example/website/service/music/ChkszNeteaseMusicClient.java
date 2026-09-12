package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.AppProperties;
import com.example.website.config.OkHttpConfig;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.NeteasePlaybackQuality;
import com.example.website.dto.music.PlayInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static com.example.website.service.music.MusicValueUtils.asInt;
import static com.example.website.service.music.MusicValueUtils.asLongObj;
import static com.example.website.service.music.MusicValueUtils.asMap;
import static com.example.website.service.music.MusicValueUtils.asString;

@Component
public class ChkszNeteaseMusicClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};
    private static final long AUTH_COOLDOWN_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final long DEFAULT_RATE_LIMIT_MILLIS = TimeUnit.SECONDS.toMillis(60);
    private static final long MAX_RATE_LIMIT_MILLIS = TimeUnit.HOURS.toMillis(24);
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private final AppProperties appProperties;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final LongSupplier clock;
    private final ThirdPartyCircuitBreaker circuitBreaker =
            new ThirdPartyCircuitBreaker("chksz-netease");
    private final Cache<String, Boolean> recentMissingUrls = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();
    private final AtomicLong cooldownUntilMillis = new AtomicLong();

    @Autowired
    public ChkszNeteaseMusicClient(AppProperties appProperties,
                                   @Qualifier(OkHttpConfig.CLIENT_MUSIC_THIRD_PARTY) OkHttpClient okHttpClient,
                                   ObjectMapper objectMapper) {
        this(appProperties, okHttpClient, objectMapper, System::currentTimeMillis);
    }

    ChkszNeteaseMusicClient(AppProperties appProperties,
                            OkHttpClient okHttpClient,
                            ObjectMapper objectMapper,
                            LongSupplier clock) {
        this.appProperties = appProperties;
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public PlayInfo resolve(String id, NeteasePlaybackQuality quality) {
        AppProperties.Chksz settings = appProperties.getMusic().getChksz();
        if (!settings.isEnabled() || isBlank(settings.getApiKey())) {
            throw noPlayable("ChKSz NetEase resolver is disabled");
        }

        String requestKey = id + ":" + quality.getValue();
        if (recentMissingUrls.getIfPresent(requestKey) != null) {
            throw noPlayable("ChKSz has no playable URL for this song and quality (recently checked)");
        }

        long now = clock.getAsLong();
        if (now < cooldownUntilMillis.get()) {
            throw upstreamFailure("ChKSz NetEase resolver is cooling down");
        }

        HttpUrl baseUrl = HttpUrl.parse(settings.getBaseUrl());
        if (baseUrl == null || !isHttp(baseUrl)) {
            throw upstreamFailure("ChKSz base URL is invalid");
        }
        HttpUrl url = baseUrl.newBuilder()
                .addPathSegments("api/163_music")
                .addQueryParameter("id", id)
                .addQueryParameter("level", quality.getValue())
                .addQueryParameter("type", "json")
                .addQueryParameter("apikey", settings.getApiKey())
                .build();

        circuitBreaker.acquire(MusicErrorCode.UPSTREAM_PLAY_FAILED);
        Call call = okHttpClient.newCall(new Request.Builder().url(url).get().build());
        MusicRequestBudget.constrain(call, 5, TimeUnit.SECONDS);

        int status;
        String retryAfter;
        String raw;
        try (Response response = call.execute()) {
            status = response.code();
            retryAfter = response.header("Retry-After");
            ResponseBody body = response.body();
            raw = body == null ? "" : body.string();
        } catch (IOException e) {
            circuitBreaker.recordFailure();
            throw upstreamFailure("ChKSz NetEase request failed", e);
        }

        if (status < 200 || status >= 300) {
            handleFailureStatus(status, retryAfter, requestKey);
        }

        Map<String, Object> json;
        try {
            json = objectMapper.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            throw upstreamFailure("ChKSz NetEase returned invalid JSON", e);
        }

        Integer code = asInt(json.get("code"));
        if (code == null || code != 200) {
            handleFailureStatus(code == null ? 500 : code, null, requestKey);
        }
        Map<String, Object> data = asMap(json.get("data"));
        if (data == null) {
            recentMissingUrls.put(requestKey, Boolean.TRUE);
            circuitBreaker.recordSuccess();
            throw noPlayable("ChKSz NetEase response has no song data");
        }

        String playUrl = asString(data.get("url"));
        HttpUrl parsedPlayUrl = HttpUrl.parse(playUrl == null ? "" : playUrl);
        if (parsedPlayUrl == null || !isHttp(parsedPlayUrl)) {
            recentMissingUrls.put(requestKey, Boolean.TRUE);
            circuitBreaker.recordSuccess();
            throw noPlayable("ChKSz NetEase response has no playable URL");
        }

        circuitBreaker.recordSuccess();
        recentMissingUrls.invalidate(requestKey);
        PlayInfo info = new PlayInfo();
        info.setId(id);
        info.setSource(MusicSource.NETEASE);
        info.setActualSource(MusicSource.NETEASE);
        info.setName(asString(data.get("name")));
        info.setArtist(asString(data.get("artist")));
        info.setAlbum(asString(data.get("album")));
        info.setCoverUrl(asString(data.get("picUrl")));
        info.setPlayUrl(playUrl);
        info.setRequestedQuality(quality.getValue());
        String actualQuality = asString(data.get("level"));
        info.setActualQuality(isBlank(actualQuality) ? quality.getValue() : actualQuality);
        info.setFileSize(asLongObj(data.get("size")));
        info.setFromCache(Boolean.FALSE);
        return info;
    }

    private void handleFailureStatus(int status, String retryAfter, String requestKey) {
        long now = clock.getAsLong();
        if (status == 400) {
            recentMissingUrls.put(requestKey, Boolean.TRUE);
            circuitBreaker.recordSuccess();
            throw noPlayable("ChKSz NetEase rejected this song or quality");
        }
        if (status == 401 || status == 403) {
            extendCooldown(now + AUTH_COOLDOWN_MILLIS);
            circuitBreaker.recordSuccess();
            throw upstreamFailure("ChKSz NetEase authorization failed");
        }
        if (status == 402) {
            extendCooldown(nextChinaMidnight(now));
            circuitBreaker.recordSuccess();
            throw upstreamFailure("ChKSz NetEase quota is exhausted");
        }
        if (status == 429) {
            extendCooldown(now + retryAfterMillis(retryAfter));
            circuitBreaker.recordSuccess();
            throw upstreamFailure("ChKSz NetEase rate limit exceeded");
        }
        if (status >= 400 && status < 500) {
            recentMissingUrls.put(requestKey, Boolean.TRUE);
            circuitBreaker.recordSuccess();
            throw noPlayable("ChKSz NetEase could not resolve this song");
        }
        circuitBreaker.recordFailure();
        throw upstreamFailure("ChKSz NetEase upstream failed with HTTP " + status);
    }

    private void extendCooldown(long untilMillis) {
        cooldownUntilMillis.updateAndGet(current -> Math.max(current, untilMillis));
    }

    private static long nextChinaMidnight(long nowMillis) {
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(CHINA_ZONE);
        return now.toLocalDate().plusDays(1).atStartOfDay(CHINA_ZONE).toInstant().toEpochMilli();
    }

    private static long retryAfterMillis(String raw) {
        if (raw == null) return DEFAULT_RATE_LIMIT_MILLIS;
        try {
            long seconds = Long.parseLong(raw.trim());
            long millis = TimeUnit.SECONDS.toMillis(Math.max(1L, seconds));
            return Math.min(millis, MAX_RATE_LIMIT_MILLIS);
        } catch (RuntimeException e) {
            return DEFAULT_RATE_LIMIT_MILLIS;
        }
    }

    private static boolean isHttp(HttpUrl url) {
        return "http".equalsIgnoreCase(url.scheme()) || "https".equalsIgnoreCase(url.scheme());
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static MusicBusinessException noPlayable(String message) {
        return new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL, message);
    }

    private static MusicBusinessException upstreamFailure(String message) {
        return new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED, message);
    }

    private static MusicBusinessException upstreamFailure(String message, Throwable cause) {
        return new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED, message, cause);
    }
}
