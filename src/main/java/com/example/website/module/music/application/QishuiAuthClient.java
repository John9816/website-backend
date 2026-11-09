package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;

import com.fasterxml.jackson.databind.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Component
public class QishuiAuthClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QishuiAuthClient.class);
    private final QishuiHttpClient http;
    private final ObjectMapper mapper;
    private final String clientKey, appIdentity, appSignature;

    public QishuiAuthClient(
            QishuiHttpClient http,
            ObjectMapper mapper,
            @Value("${app.music.qishui.client-key:}") String clientKey,
            @Value("${app.music.qishui.app-identity:}") String appIdentity,
            @Value("${app.music.qishui.app-signature:}") String appSignature) {
        this.http = http;
        this.mapper = mapper;
        this.clientKey = clientKey;
        this.appIdentity = appIdentity;
        this.appSignature = appSignature;
    }

    private Map<String, String> qrParams() {
        if (empty(clientKey) || empty(appIdentity) || empty(appSignature))
            throw new MusicBusinessException(
                    MusicErrorCode.MISSING_UPSTREAM_TOKEN,
                    "Qishui OAuth application parameters are not configured");
        Map<String, String> q = new LinkedHashMap<>();
        q.put("aid", "1128");
        q.put("client_key", clientKey);
        q.put(
                "scope",
                "user_info,luna.openapi.platform.interaction,luna.openapi.platform.me_tab,luna.commerce.openapi_platform.vip_info");
        for (String key :
                Arrays.asList("state", "next", "optional_scope_check", "optional_scope_uncheck"))
            q.put(key, "");
        q.put("jump_type", "native");
        q.put("customize_params", "{\"source\":\"pc_auth\",\"not_skip_confirm\":\"true\"}");
        q.put("source_from", "native");
        q.put("device_platform", "android");
        q.put("app_identity", appIdentity);
        q.put("signature", appSignature);
        return q;
    }

    LoginStart start() {
        JsonNode d =
                QishuiHttpClient.checked(
                        http.get("https://open.douyin.com/oauth/get_qrcode/", qrParams(), null)
                                .json);
        return new LoginStart(
                required(d, "token"), required(d, "qrcode"), required(d, "qrcode_index_url"));
    }

    JsonNode poll(String token) {
        Map<String, String> q = qrParams();
        q.put("token", token);
        q.put("timestamp", Long.toString(System.currentTimeMillis()));
        return stage("poll", "读取扫码状态", () -> QishuiHttpClient.checked(
                http.get("https://open.douyin.com/oauth/check_qrcode/", q, null).json));
    }

    QishuiCredential complete(String code, String deviceId, QishuiGuard guard) {
        Map<String, String> q = QishuiHttpClient.common(deviceId);
        q.put("auth_code", code);
        JsonNode access = stage("access", "获取授权", () ->
                QishuiHttpClient.checked(
                        http.get(QishuiHttpClient.OPEN + "/access/code/get/", q, null).json));
        QishuiCredential credential = stage("ticket", "交换登录凭据", () -> exchange(access, deviceId, guard, null));
        stage("profile", "读取账号资料", () -> { profile(credential); return credential; });
        return credential;
    }

    QishuiCredential refresh(QishuiCredential current) {
        Map<String, String> q = QishuiHttpClient.common(current.getDeviceId());
        q.put("access_code", current.getAccessCode());
        JsonNode access =
                QishuiHttpClient.checked(
                        http.get(QishuiHttpClient.OPEN + "/access/code/refresh/", q, null).json);
        QishuiCredential updated =
                exchange(
                        access,
                        current.getDeviceId(),
                        QishuiGuard.restore(current.getPrivateScalar()),
                        current.getOpenId());
        profile(updated);
        return updated;
    }

    private QishuiCredential exchange(
            JsonNode access, String deviceId, QishuiGuard guard, String previousOpenId) {
        if (empty(clientKey))
            throw new MusicBusinessException(
                    MusicErrorCode.MISSING_UPSTREAM_TOKEN,
                    "Qishui OAuth application parameters are not configured");
        String accessCode = required(access, "access_code"),
                openId =
                        access.path("open_id")
                                .asText(access.path("open_uid").asText(previousOpenId));
        if (empty(openId)) throw QishuiHttpClient.failure();
        Map<String, String> q = new LinkedHashMap<>();
        q.put("aid", "1128");
        q.put("client_key", clientKey);
        q.put("device_platform", "android");
        Map<String, String> body = new LinkedHashMap<>();
        body.put("access_code", accessCode);
        body.put("open_id", openId);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("bd-ticket-guard-version", "1");
        headers.put("bd-ticket-guard-client-csr", guard.csr());
        QishuiHttpClient.Result r =
                http.form(
                        "https://open-client.douyin.com/passport/open/get_access_ticket/",
                        q,
                        body,
                        headers);
        JsonNode d = QishuiHttpClient.checked(r.json);
        try {
            String encoded = r.headers.get("bd-ticket-guard-server-data");
            if (encoded == null || encoded.length() > 65536) throw QishuiHttpClient.failure();
            JsonNode server = mapper.readTree(Base64.getDecoder().decode(encoded));
            String cert = required(server, "client_cert");
            if (cert.startsWith("-----BEGIN"))
                cert = Base64.getEncoder().encodeToString(cert.getBytes(StandardCharsets.UTF_8));
            Instant expires = expiry(access.path("expires_time"), Instant.now());
            QishuiCredential c = new QishuiCredential();
            c.setPrivateScalar(guard.privateScalar());
            c.setDeviceId(deviceId);
            c.setAccessCode(accessCode);
            c.setOpenId(openId);
            c.setTicket(required(d, "ticket"));
            c.setGuardCert(cert);
            c.setGuardTsSign(required(server, "ts_sign"));
            c.setExpiresAt(expires);
            return c;
        } catch (java.io.IOException | IllegalArgumentException ex) {
            throw QishuiHttpClient.failure();
        }
    }

    private void profile(QishuiCredential c) {
        JsonNode root =
                http.post(
                                QishuiHttpClient.PLATFORM + "/me/info/",
                                QishuiHttpClient.common(c.getDeviceId()),
                                Collections.singletonMap("common_params", Collections.emptyMap()),
                                QishuiGuard.restore(c.getPrivateScalar())
                                        .headers(c, mapper, Instant.now().getEpochSecond()))
                        .json;
        JsonNode me = QishuiHttpClient.checked(root).path("my_info");
        c.setUserId(required(me, "id"));
        QishuiCredential.Profile p = new QishuiCredential.Profile();
        p.setNickname(me.path("nickname").asText(null));
        p.setAvatarUrl(QishuiMusicClient.imageUrl(me.path("medium_avatar_url")));
        p.setIsVip(me.path("is_vip").asBoolean());
        c.setProfile(p);
    }

    static Instant expiry(JsonNode value, Instant now) {
        long n = value.isMissingNode() || value.isNull() ? 21600 : value.asLong(-1);
        if (n <= 0) throw QishuiHttpClient.failure();
        if (n > 500000000000L) return Instant.ofEpochMilli(n);
        if (n > 1000000000L) return Instant.ofEpochSecond(n);
        return now.plusSeconds(n);
    }

    static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isEmpty()) {
            log.warn("Qishui response missing required field={}", field);
            throw QishuiHttpClient.failure();
        }
        return value;
    }

    private <T> T stage(String stage, String label, java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (MusicBusinessException ex) {
            log.warn("Qishui auth failed stage={} code={}", stage, ex.getErrorCode().getCode());
            throw new MusicBusinessException(ex.getErrorCode(), "黑源" + label + "失败，请重新获取二维码后扫码");
        }
    }

    private static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }

    static final class LoginStart {
        final String token, qrcode, qrcodeUrl;

        LoginStart(String token, String qrcode, String qrcodeUrl) {
            this.token = token;
            this.qrcode = qrcode;
            this.qrcodeUrl = qrcodeUrl;
        }
    }
}
