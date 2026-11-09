package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.OkHttpConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Serves plain, browser-playable Qishui audio by composing the whole TuneFree v1 chain:
 * obtain an access token, call {@code /v1/parse}, download the CENC-encrypted MP4, decrypt it
 * in-process, and cache the plain bytes.
 *
 * <p>The upstream play URL is never handed to the browser: it is short-lived, on a foreign host
 * (CORS), and encrypted. Instead {@link QishuiParseClient} returns a signed local stream URL,
 * and this service produces the bytes behind it.
 *
 * <p>Caching is two-tier. A small in-memory Caffeine cache (64 MB) serves hot tracks instantly;
 * a bounded on-disk cache (1 GiB) holds the rest, because a lossless track is ~33 MB and the API
 * only has a 768 MB heap. {@code Cache.get(key, loader)} makes the load atomic per key, so the
 * several concurrent HTTP Range requests a browser issues while starting playback cannot trigger
 * duplicate upstream downloads and decryptions.
 */
@Slf4j
@Service
public class QishuiStreamService {

    private static final String PARSE_URL = "https://tunefree.sayqz.com/v1/parse";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long MAX_AUDIO_BYTES = 48L * 1024 * 1024;   // guard against runaway responses
    private static final long CACHE_MAX_BYTES = 64L * 1024 * 1024;    // in-memory hot set; must stay small on a 768m heap
    private static final long DISK_CACHE_MAX_BYTES = 1024L * 1024 * 1024;
    private static final long CACHE_TTL_SEC = 1100L;

    private final TuneFreeV1AuthService auth;
    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final Cache<String, byte[]> plainCache;
    private final QishuiDiskCache diskCache;

    public QishuiStreamService(
            TuneFreeV1AuthService auth,
            @Qualifier(OkHttpConfig.CLIENT_MUSIC_THIRD_PARTY) OkHttpClient baseClient,
            ObjectMapper mapper,
            @Value("${app.music.qishui.cache-dir:/opt/website/cache/qishui-audio}") String cacheDir) {
        this.auth = auth;
        this.http = baseClient.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build();
        this.mapper = mapper;
        this.diskCache = new QishuiDiskCache(cacheDir, DISK_CACHE_MAX_BYTES, CACHE_TTL_SEC);
        this.plainCache = Caffeine.newBuilder()
                .maximumWeight(CACHE_MAX_BYTES)
                .weigher((String k, byte[] v) -> v.length)
                .expireAfterWrite(Duration.ofSeconds(CACHE_TTL_SEC))
                .build();
    }

    /** Returns plain MP4 bytes for the given track, using both cache tiers when possible. */
    public byte[] plaintext(String trackId, String quality) {
        String key = cacheKey(trackId, quality);
        // Atomic per-key load: concurrent Range requests share a single upstream resolve.
        return plainCache.get(key, k -> load(k, trackId, quality));
    }

    private byte[] load(String key, String trackId, String quality) {
        byte[] disk = diskCache.read(key);
        if (disk != null) {
            log.info("Qishui stream disk cache hit quality={} bytes={}", quality, disk.length);
            return disk;
        }
        byte[] plain = resolvePlaintext(trackId, quality);
        diskCache.write(key, plain);
        return plain;
    }

    /**
     * Upstream {@code flac24bit} returns {@code actualQuality=flac} and byte-identical audio, so
     * both qualities share one cache entry. This halves the download/decrypt/memory cost for the
     * Hi-Res option without changing what the browser receives.
     */
    static String cacheKey(String trackId, String quality) {
        return trackId + "|" + normalizeQuality(quality);
    }

    static String normalizeQuality(String quality) {
        if (quality == null) {
            return "";
        }
        return "flac24bit".equalsIgnoreCase(quality) ? "flac" : quality;
    }

    private byte[] resolvePlaintext(String trackId, String quality) {
        ParseResult parsed = parseWithRetry(trackId, quality);
        byte[] encrypted = download(parsed.playUrl);
        try {
            byte[] plain = QishuiCencDecryptor.decrypt(encrypted, parsed.playAuth);
            log.info("Qishui stream ready trackLength={} quality={} bytes={}",
                    trackId.length(), quality, plain.length);
            return plain;
        } catch (GeneralSecurityException e) {
            log.warn("Qishui CENC decrypt failed: {}", e.getMessage());
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "failed to decode Qishui audio: " + e.getMessage(), e);
        }
    }

    private ParseResult parseWithRetry(String trackId, String quality) {
        try {
            return parse(trackId, quality, auth.getToken());
        } catch (MusicBusinessException first) {
            // A revoked/expired token is the common cause: force one clean re-login.
            log.info("Qishui parse failed, retrying once with a fresh v1 token: {}", first.getMessage());
            auth.invalidate();
            return parse(trackId, quality, auth.getToken());
        }
    }

    private ParseResult parse(String trackId, String quality, String token) {
        JsonNode body = mapper.createObjectNode()
                .put("source", "qs")
                .put("trackId", trackId)
                .put("quality", quality);
        Request req = new Request.Builder()
                .url(PARSE_URL)
                .header("User-Agent", "tunefree-next-bff/1.0")
                .header("Authorization", "Bearer " + token)
                .post(RequestBody.create(JSON, body.toString()))
                .build();
        JsonNode root;
        try (Response resp = http.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            String raw = rb == null ? "" : rb.string();
            if (resp.code() == 401 || resp.code() == 403) {
                auth.invalidate();
                throw new MusicBusinessException(MusicErrorCode.MISSING_UPSTREAM_TOKEN,
                        "TuneFree v1 rejected the access token (HTTP " + resp.code() + ")");
            }
            if (resp.code() < 200 || resp.code() >= 300) {
                throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                        "TuneFree v1 parse returned HTTP " + resp.code());
            }
            root = mapper.readTree(raw);
        } catch (InterruptedIOException e) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT,
                    "TuneFree v1 parse timed out", e);
        } catch (IOException e) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "TuneFree v1 parse I/O failed: " + e.getMessage(), e);
        }
        if (root == null || !root.path("success").asBoolean(false)) {
            String code = root == null ? "?" : root.path("error").path("code").asText("?");
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "TuneFree v1 parse rejected: " + code);
        }
        JsonNode data = root.path("data");
        String playUrl = data.path("playUrl").asText("");
        JsonNode encryption = data.path("encryption");
        String playAuth = null;
        if (encryption.isObject()) {
            for (String field : new String[] {"spadeA", "spade_a", "SpadeA", "playAuth", "play_auth"}) {
                if (encryption.path(field).isTextual() && !encryption.path(field).asText("").isEmpty()) {
                    playAuth = encryption.path(field).asText();
                    break;
                }
            }
        }
        if (playUrl.isEmpty() || playAuth == null) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "TuneFree v1 parse response missing playUrl or play auth");
        }
        if (!playUrl.startsWith("https://") && !playUrl.startsWith("http://")) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "TuneFree v1 returned an invalid play URL");
        }
        return new ParseResult(playUrl, playAuth);
    }

    private byte[] download(String url) {
        Request req = new Request.Builder().url(url)
                .header("User-Agent", "tunefree-next-bff/1.0")
                .get().build();
        try (Response resp = http.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            if (resp.code() < 200 || resp.code() >= 300 || rb == null) {
                throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                        "Qishui audio download returned HTTP " + resp.code());
            }
            long declared = rb.contentLength();
            if (declared > MAX_AUDIO_BYTES) {
                throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                        "Qishui audio exceeds size limit");
            }
            byte[] data = rb.bytes();
            if (data.length > MAX_AUDIO_BYTES) {
                throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                        "Qishui audio exceeds size limit");
            }
            return data;
        } catch (InterruptedIOException e) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT,
                    "Qishui audio download timed out", e);
        } catch (IOException e) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED,
                    "Qishui audio download failed: " + e.getMessage(), e);
        }
    }

    private static final class ParseResult {
        final String playUrl;
        final String playAuth;

        ParseResult(String playUrl, String playAuth) {
            this.playUrl = playUrl;
            this.playAuth = playAuth;
        }
    }
}
