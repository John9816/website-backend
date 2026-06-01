package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.OkHttpConfig;
import com.example.website.service.SysConfigService;
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
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Owns the TuneFreeNext login token. Reads account/password/udid from
 * sys_config, calls the logon endpoint, keeps the token in a volatile field,
 * and writes the latest token back to sys_config for observability and
 * cross-process reuse.
 *
 * <p>Token refresh is failure-driven: the caller (tf-pay client) retries
 * once with force=true when an upstream call looks token-related.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TuneFreeAuthService {

    public static final String CFG_ACCOUNT            = "music.tunefree.account";
    public static final String CFG_PASSWORD           = "music.tunefree.password";
    public static final String CFG_UDID               = "music.tunefree.udid";
    public static final String CFG_TOKEN              = "music.tunefree.token";
    public static final String CFG_TOKEN_UPDATED_AT   = "music.tunefree.token_updated_at";
    public static final String CFG_TOKEN_STATUS       = "music.tunefree.token_status";

    private static final String LOGON_URL    = "https://ums.sayqz.com/api/user/1000/V3/3.0.9/logon";
    private static final String DEFAULT_UDID = "TUNEFREENEXT_BFF_001";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    private final SysConfigService configService;
    @Qualifier(OkHttpConfig.CLIENT_QUICK)
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    private volatile String cachedToken;

    @PostConstruct
    public void init() {
        this.cachedToken = configService.getValue(CFG_TOKEN).orElse(null);
    }

    public String getToken() {
        String t = cachedToken;
        if (t != null && !t.isEmpty()) return t;
        return refresh(false);
    }

    public synchronized String refresh(boolean force) {
        if (!force) {
            String t = cachedToken;
            if (t != null && !t.isEmpty()) return t;
        }

        String account = configService.getValue(CFG_ACCOUNT).orElse(null);
        String password = configService.getValue(CFG_PASSWORD).orElse(null);
        String udid = configService.getValue(CFG_UDID).filter(s -> !s.isEmpty()).orElse(DEFAULT_UDID);

        if (account == null || account.isEmpty() || password == null || password.isEmpty()) {
            markStatus("failed:missing-credentials");
            throw new MusicBusinessException(MusicErrorCode.MISSING_UPSTREAM_TOKEN,
                    "music.tunefree.account / music.tunefree.password not configured in sys_config");
        }

        HttpUrl url = HttpUrl.parse(LOGON_URL).newBuilder()
                .addQueryParameter("account", account)
                .addQueryParameter("password", password)
                .addQueryParameter("udid", udid)
                .build();

        Request request = new Request.Builder().url(url).get().build();

        String rawBody;
        int status;
        try (Response response = okHttpClient.newCall(request).execute()) {
            status = response.code();
            ResponseBody rb = response.body();
            rawBody = rb == null ? "" : rb.string();
        } catch (IOException e) {
            markStatus("failed:io");
            log.warn("TuneFree logon I/O failed: {}", e.getMessage());
            throw new MusicBusinessException(MusicErrorCode.MISSING_UPSTREAM_TOKEN,
                    "tunefree logon I/O failed: " + e.getMessage(), e);
        }

        Map<String, Object> json;
        try {
            json = objectMapper.readValue(rawBody, MAP_TYPE);
        } catch (Exception e) {
            markStatus("failed:non-json");
            log.warn("TuneFree logon returned non-JSON (status={}): {}", status, truncate(rawBody));
            throw new MusicBusinessException(MusicErrorCode.MISSING_UPSTREAM_TOKEN,
                    "tunefree logon returned non-JSON (status=" + status + ")", e);
        }

        Object code = json.get("code");
        if (!(code instanceof Number) || ((Number) code).intValue() != 0) {
            String msg = String.valueOf(json.getOrDefault("msg", "unknown"));
            markStatus("failed:code=" + code);
            log.warn("TuneFree logon rejected: code={}, msg={}", code, msg);
            throw new MusicBusinessException(MusicErrorCode.MISSING_UPSTREAM_TOKEN,
                    "tunefree logon failed: " + msg);
        }

        Object data = json.get("data");
        String token = null;
        if (data instanceof Map) {
            Object t = ((Map<?, ?>) data).get("token");
            if (t instanceof String) token = (String) t;
        }
        if (token == null || token.isEmpty()) {
            markStatus("failed:empty-token");
            throw new MusicBusinessException(MusicErrorCode.MISSING_UPSTREAM_TOKEN,
                    "tunefree logon response missing data.token");
        }

        this.cachedToken = token;
        configService.upsertByKey(CFG_TOKEN, token, "TuneFreeNext token, refreshed from logon endpoint");
        configService.upsertByKey(CFG_TOKEN_UPDATED_AT, OffsetDateTime.now().toString(), null);
        configService.upsertByKey(CFG_TOKEN_STATUS, "ok", null);

        log.info("TuneFree token refreshed (len={})", token.length());
        return token;
    }

    private void markStatus(String status) {
        try {
            configService.upsertByKey(CFG_TOKEN_STATUS, status, null);
            configService.upsertByKey(CFG_TOKEN_UPDATED_AT, OffsetDateTime.now().toString(), null);
        } catch (Exception ignored) {
        }
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
