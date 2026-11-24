package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.OkHttpConfig;
import com.example.website.module.music.api.dto.MusicQuality;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.PlayInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.example.website.module.music.application.MusicValueUtils.asString;
import static com.example.website.module.music.application.MusicValueUtils.firstNonEmpty;
import static com.example.website.module.music.application.MusicValueUtils.truncate;

/**
 * TuneFree v1 unified play-URL gateway client ({@code tunefree.sayqz.com/v1/parse}).
 *
 * <p>Uses the TuneFree v1 access token owned by {@link TuneFreeV1AuthService} (same
 * account/password as tf-pay, separate token space). On 401/403 or an auth-flavoured
 * rejection, forces one clean re-login and retries once.
 *
 * <p>Request:  {@code POST /v1/parse} with JSON {@code {source, trackId, quality}} and
 * {@code Authorization: Bearer <v1 token>}. Note a successful parse returns HTTP
 * <b>201</b> (not 200), so any 2xx is treated as success.
 *
 * <p>Source identifiers differ from {@link MusicSource#getValue()}:
 * QQ→{@code qq}, NETEASE→{@code wy}, KUWO→{@code kw}. Using the enum value for
 * Netease/Kuwo yields {@code PARSE_SOURCE_UNSUPPORTED}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TuneFreeV1PlayClient {

    static final String PARSE_URL = "https://tunefree.sayqz.com/v1/parse";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};

    private final TuneFreeV1AuthService auth;
    @Qualifier(OkHttpConfig.CLIENT_MUSIC_THIRD_PARTY)
    private final OkHttpClient okHttpClient;
    private final ObjectMapper mapper;

    private final ThirdPartyCircuitBreaker circuitBreaker =
            new ThirdPartyCircuitBreaker("tunefree-v1-parse");

    /** Play via the v1 gateway. Never returns null; throws on failure so callers can fall back. */
    public PlayInfo play(MusicSource source, String id, MusicQuality quality) {
        String token = auth.getToken();
        ParseResult r = call(source, id, quality, token);
        if (r.ok) {
            circuitBreaker.recordSuccess();
            return toPlayInfo(source, id, quality, r.data);
        }
        if (r.authFailure) {
            log.info("TuneFree v1 parse rejected token, refreshing once. reason={}", r.reason);
            auth.invalidate();
            token = auth.getToken();
            r = call(source, id, quality, token);
            if (r.ok) {
                circuitBreaker.recordSuccess();
                return toPlayInfo(source, id, quality, r.data);
            }
        }
        if (r.contentUnavailable) {
            throw new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL,
                    "v1 parse content unavailable for " + source.getValue() + ":" + id);
        }
        circuitBreaker.recordFailure();
        throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                "v1 parse failed: " + r.reason);
    }

    private ParseResult call(MusicSource source, String id, MusicQuality quality, String token) {
        String v1Source = v1Source(source);
        String q = quality == null ? MusicQuality.K320.getValue() : quality.getValue();
        JsonNode body = mapper.createObjectNode()
                .put("source", v1Source)
                .put("trackId", id)
                .put("quality", q);
        Request req = new Request.Builder()
                .url(PARSE_URL)
                .header("User-Agent", "tunefree-next-bff/1.0")
                .header("Authorization", "Bearer " + token)
                .post(RequestBody.create(JSON, mapper.valueToTree(body).toString()))
                .build();

        String raw;
        int status;
        try (ThirdPartyCircuitBreaker.Attempt ignored = circuitBreaker.acquire(MusicErrorCode.UPSTREAM_PLAY_FAILED)) {
            Call call = okHttpClient.newCall(req);
            MusicRequestBudget.constrain(call, 5, TimeUnit.SECONDS);
            try (Response resp = call.execute()) {
                status = resp.code();
                ResponseBody rb = resp.body();
                raw = rb == null ? "" : rb.string();
            } catch (IOException e) {
                MusicRequestBudget.ensureNotCancelled();
                return ParseResult.fail("io: " + e.getMessage(), false, false);
            }
            MusicRequestBudget.ensureNotCancelled();

            if (status == 401 || status == 403) {
                return ParseResult.fail("http " + status, true, false);
            }
            // A successful parse returns 201; accept any 2xx. Error responses
            // (e.g. 422) still carry a JSON body with an error code worth inspecting.
            JsonNode root;
            try {
                root = raw.isEmpty() ? null : mapper.readTree(raw);
            } catch (Exception e) {
                root = null;
            }
            if (status < 200 || status >= 300) {
                String code = root == null ? "" : root.path("error").path("code").asText("");
                boolean contentUnavailable = isContentUnavailable(code);
                boolean auth = hasAuthHint(code) || hasAuthHint(raw);
                return ParseResult.fail("http " + status + ": " + truncate(raw), auth, contentUnavailable);
            }
            if (root == null || !root.path("success").asBoolean(false)) {
                String code = root == null ? "?" : root.path("error").path("code").asText("?");
                boolean auth = hasAuthHint(code);
                boolean contentUnavailable = isContentUnavailable(code);
                return ParseResult.fail("rejected: " + code, auth, contentUnavailable);
            }
            JsonNode data = root.path("data");
            String playUrl = data.path("playUrl").asText("");
            if (playUrl == null || playUrl.isEmpty()) {
                return ParseResult.fail("empty playUrl", false, false);
            }
            Map<String, Object> dataMap;
            try {
                dataMap = mapper.readValue(mapper.valueToTree(data).toString(), MAP_TYPE);
            } catch (Exception e) {
                dataMap = new java.util.HashMap<>();
            }
            dataMap.put("playUrl", playUrl);
            return ParseResult.ok(dataMap);
        }
    }

    private PlayInfo toPlayInfo(MusicSource source, String id, MusicQuality quality, Map<String, Object> d) {
        PlayInfo p = new PlayInfo();
        p.setId(id);
        p.setSource(source);
        p.setActualSource(source);
        p.setPlayUrl(asString(d.get("playUrl")));
        p.setRequestedQuality(firstNonEmpty(
                asString(d.get("requestedQuality")), quality == null ? null : quality.getValue()));
        p.setActualQuality(asString(d.get("actualQuality")));
        p.setExpireSec(parseExpire(asString(d.get("expiresAt"))));
        return p;
    }

    /** expiresAt is an ISO-8601 timestamp; convert to seconds-from-now if parseable. */
    static Integer parseExpire(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        try {
            OffsetDateTime t = OffsetDateTime.parse(iso);
            long sec = java.time.Duration.between(OffsetDateTime.now(), t).getSeconds();
            return sec > 0 && sec <= Integer.MAX_VALUE ? (int) sec : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String v1Source(MusicSource source) {
        if (source == null) throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE, "source is required");
        switch (source) {
            case QQ: return "qq";
            case NETEASE: return "wy";
            case KUWO: return "kw";
            default: throw new MusicBusinessException(MusicErrorCode.UNSUPPORTED_OPERATION,
                    "source has no v1 parse mapping: " + source.getValue());
        }
    }

    private static boolean isContentUnavailable(String code) {
        return "PARSE_CONTENT_UNAVAILABLE".equals(code) || "PARSE_SOURCE_UNSUPPORTED".equals(code);
    }

    private static boolean hasAuthHint(String s) {
        if (s == null) return false;
        String n = s.toLowerCase(Locale.ROOT);
        return n.contains("token") || n.contains("unauthorized") || n.contains("login")
                || n.contains("登录") || n.contains("过期") || n.contains("认证");
    }

    private static final class ParseResult {
        final boolean ok;
        final Map<String, Object> data;
        final String reason;
        final boolean authFailure;
        final boolean contentUnavailable;

        private ParseResult(boolean ok, Map<String, Object> data, String reason,
                            boolean authFailure, boolean contentUnavailable) {
            this.ok = ok;
            this.data = data;
            this.reason = reason;
            this.authFailure = authFailure;
            this.contentUnavailable = contentUnavailable;
        }

        static ParseResult ok(Map<String, Object> data) {
            return new ParseResult(true, data, null, false, false);
        }

        static ParseResult fail(String reason, boolean authFailure, boolean contentUnavailable) {
            return new ParseResult(false, null, reason, authFailure, contentUnavailable);
        }
    }
}
