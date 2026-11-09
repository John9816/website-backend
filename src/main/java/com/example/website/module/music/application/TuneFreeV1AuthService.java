package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.OkHttpConfig;
import com.example.website.module.settings.application.SysConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Owns the TuneFree <b>v1</b> access token used by {@code /v1/parse}.
 *
 * <p>The v1 service is a separate account/token space from the legacy tf-pay service, but it
 * accepts the same account/password that are already stored in {@code sys_config}
 * ({@code music.tunefree.account} / {@code music.tunefree.password}). This service therefore
 * logs in automatically, keeps the access token in memory, and refreshes it before expiry using
 * the refresh token — no manual {@code .env} entry and no long-lived static token to rotate.
 *
 * <p>A statically configured {@code app.music.qishui.parse-access-token} (if present) takes
 * precedence, so an operator can still pin a token explicitly. Nothing here writes credentials
 * to logs; only token lengths and HTTP statuses are recorded.
 */
@Slf4j
@Service
public class TuneFreeV1AuthService {

    private static final String LOGIN_URL = "https://tunefree.sayqz.com/v1/auth/login";
    private static final String REFRESH_URL = "https://tunefree.sayqz.com/v1/auth/refresh";
    private static final String DEVICE_NAME = "TUNEFREENEXT_BFF_001";
    private static final String PLATFORM = "windows";
    private static final long REFRESH_SKEW_SEC = 60L;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final SysConfigService configService;
    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String staticToken;

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile long expiresAtMillis;
    private volatile long deviceIdSeed = System.currentTimeMillis();

    public TuneFreeV1AuthService(
            SysConfigService configService,
            @Qualifier(OkHttpConfig.CLIENT_MUSIC_THIRD_PARTY) OkHttpClient http,
            ObjectMapper mapper,
            @Value("${app.music.qishui.parse-access-token:}") String staticToken) {
        this.configService = configService;
        this.http = http.newBuilder()
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .build();
        this.mapper = mapper;
        this.staticToken = staticToken == null ? "" : staticToken.trim();
        this.accessToken = this.staticToken.isEmpty() ? null : this.staticToken;
        if (!this.staticToken.isEmpty()) {
            this.expiresAtMillis = Long.MAX_VALUE; // static token never auto-refreshes
        }
    }

    /** Returns a usable v1 access token, logging in or refreshing as needed. */
    public String getToken() {
        if (!staticToken.isEmpty()) return staticToken;
        String t = accessToken;
        if (t != null && !t.isEmpty() && System.currentTimeMillis() < expiresAtMillis) {
            return t;
        }
        synchronized (this) {
            t = accessToken;
            if (t != null && !t.isEmpty() && System.currentTimeMillis() < expiresAtMillis) {
                return t;
            }
            if (refreshToken != null && !refreshToken.isEmpty() && tryRefresh()) {
                return accessToken;
            }
            login();
            return accessToken;
        }
    }

    /** Forces the next {@link #getToken()} to re-authenticate (used after a 401 upstream). */
    public synchronized void invalidate() {
        if (!staticToken.isEmpty()) return;
        accessToken = null;
        refreshToken = null;
        expiresAtMillis = 0L;
    }

    private boolean tryRefresh() {
        try {
            JsonNode data = post(REFRESH_URL,
                    mapper.createObjectNode().put("refreshToken", refreshToken), null);
            return adopt(data);
        } catch (RuntimeException e) {
            log.warn("TuneFree v1 refresh failed, falling back to login: {}", e.getMessage());
            return false;
        }
    }

    private void login() {
        String account = configService.getValue("music.tunefree.account").orElse(null);
        String password = configService.getValue("music.tunefree.password").orElse(null);
        if (account == null || account.isEmpty() || password == null || password.isEmpty()) {
            throw new MusicBusinessException(MusicErrorCode.MISSING_UPSTREAM_TOKEN,
                    "TuneFree v1 credentials (music.tunefree.account/password) are not configured");
        }
        JsonNode body = mapper.createObjectNode()
                .put("email", account)
                .put("password", password)
                .put("deviceId", UUID.randomUUID().toString())
                .put("platform", PLATFORM)
                .put("deviceName", DEVICE_NAME);
        JsonNode data = post(LOGIN_URL, body, null);
        if (!adopt(data)) {
            throw new MusicBusinessException(MusicErrorCode.MISSING_UPSTREAM_TOKEN,
                    "TuneFree v1 login response missing accessToken");
        }
        log.info("TuneFree v1 login succeeded (accessTokenLen={})",
                accessToken == null ? 0 : accessToken.length());
    }

    private boolean adopt(JsonNode data) {
        if (data == null || !data.path("accessToken").isTextual()) return false;
        String at = data.path("accessToken").asText("");
        if (at.isEmpty()) return false;
        String rt = data.path("refreshToken").asText("");
        long expiresIn = data.path("accessTokenExpiresIn").asLong(900L);
        if (expiresIn <= 0) expiresIn = 900L;
        this.accessToken = at;
        if (!rt.isEmpty()) this.refreshToken = rt;
        this.expiresAtMillis = System.currentTimeMillis()
                + Math.max(30L, expiresIn - REFRESH_SKEW_SEC) * 1000L;
        return true;
    }

    private JsonNode post(String url, JsonNode body, String bearer) {
        Request.Builder b = new Request.Builder().url(url)
                .header("User-Agent", "tunefree-next-bff/1.0")
                .post(RequestBody.create(JSON, mapper.valueToTree(body).toString()));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        try (Response resp = http.newCall(b.build()).execute()) {
            ResponseBody rb = resp.body();
            String raw = rb == null ? "" : rb.string();
            if (resp.code() < 200 || resp.code() >= 300) {
                throw new MusicBusinessException(MusicErrorCode.MISSING_UPSTREAM_TOKEN,
                        "TuneFree v1 auth returned HTTP " + resp.code());
            }
            JsonNode root = mapper.readTree(raw);
            if (root == null || !root.path("success").asBoolean(false)) {
                String code = root == null ? "?" : root.path("error").path("code").asText("?");
                throw new MusicBusinessException(MusicErrorCode.MISSING_UPSTREAM_TOKEN,
                        "TuneFree v1 auth rejected: " + code);
            }
            return root.path("data");
        } catch (IOException e) {
            throw new MusicBusinessException(MusicErrorCode.MISSING_UPSTREAM_TOKEN,
                    "TuneFree v1 auth I/O failed: " + e.getMessage(), e);
        }
    }
}
