package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.config.AppProperties;
import com.example.website.config.OkHttpConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CloudMailClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    private volatile CloudMailSession cachedSession;

    public CloudMailClient(@Qualifier(OkHttpConfig.CLIENT_QUICK) OkHttpClient okHttpClient,
                           ObjectMapper objectMapper,
                           AppProperties props) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public void sendRegistrationCode(String recipient, String code, long expireMinutes) {
        sendVerificationCode(recipient, code, expireMinutes, "注册", "751152.xyz 注册验证码");
    }

    public void sendPasswordResetCode(String recipient, String code, long expireMinutes) {
        sendVerificationCode(recipient, code, expireMinutes, "密码重置", "751152.xyz 密码重置验证码");
    }

    private void sendVerificationCode(String recipient,
                                      String code,
                                      long expireMinutes,
                                      String purpose,
                                      String subject) {
        AppProperties.CloudMail settings = settings();
        assertConfigured(settings);

        for (int attempt = 0; attempt < 2; attempt++) {
            CloudMailSession session = session(settings);
            try {
                send(settings, session, recipient, code, expireMinutes, purpose, subject);
                return;
            } catch (CloudMailUnauthorizedException ex) {
                cachedSession = null;
                if (attempt == 1) {
                    throw new BusinessException(503, "Cloud Mail 登录已失效，请检查账号配置");
                }
            }
        }
    }

    private void send(AppProperties.CloudMail settings,
                      CloudMailSession session,
                      String recipient,
                      String code,
                      long expireMinutes,
                      String purpose,
                      String subject) {
        String text = "您的" + purpose + "验证码是：" + code + "。验证码 "
                + expireMinutes + " 分钟内有效，请勿告知他人。";
        String content = "<p>您的" + purpose + "验证码是：<strong style=\"font-size:24px\">" + code
                + "</strong></p><p>验证码 " + expireMinutes + " 分钟内有效，请勿告知他人。</p>";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sendEmail", settings.getFrom());
        payload.put("receiveEmail", Collections.singletonList(recipient));
        payload.put("accountId", session.accountId);
        payload.put("name", "751152.xyz");
        payload.put("subject", subject);
        payload.put("content", content);
        payload.put("text", text);
        payload.put("sendType", "");
        payload.put("emailId", 0);
        payload.put("attachments", Collections.emptyList());
        payload.put("draftId", null);
        payload.put("manyType", null);

        post(settings, "/email/send", payload, session.token);
    }

    private CloudMailSession session(AppProperties.CloudMail settings) {
        CloudMailSession existing = cachedSession;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (cachedSession == null) {
                cachedSession = login(settings);
            }
            return cachedSession;
        }
    }

    private CloudMailSession login(AppProperties.CloudMail settings) {
        Map<String, Object> loginPayload = new LinkedHashMap<>();
        loginPayload.put("email", settings.getUsername());
        loginPayload.put("password", settings.getPassword());

        Object loginData;
        try {
            loginData = post(settings, "/login", loginPayload, null);
        } catch (CloudMailUnauthorizedException ex) {
            throw new BusinessException(503, "Cloud Mail 账号或密码不正确");
        }
        String token = stringValue(asMap(loginData).get("token"));
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(503, "Cloud Mail 登录未返回 Token");
        }

        long accountId = findSenderAccountId(settings, token);
        if (accountId <= 0) {
            throw new BusinessException(503, "Cloud Mail 中未找到发件邮箱 " + settings.getFrom());
        }
        return new CloudMailSession(token, accountId);
    }

    private long findSenderAccountId(AppProperties.CloudMail settings, String token) {
        HttpUrl url = HttpUrl.parse(apiUrl(settings, "/account/list"));
        if (url == null) {
            throw new BusinessException(503, "Cloud Mail 地址配置不正确");
        }
        url = url.newBuilder()
                .addQueryParameter("accountId", "0")
                .addQueryParameter("size", "100")
                .build();
        Object data = execute(new Request.Builder().url(url).header("Authorization", token).get().build());
        if (!(data instanceof List)) {
            return 0L;
        }
        for (Object item : (List<?>) data) {
            Map<String, Object> account = asMap(item);
            if (settings.getFrom().equalsIgnoreCase(stringValue(account.get("email")))) {
                return longValue(account.get("accountId"));
            }
        }
        return 0L;
    }

    private Object post(AppProperties.CloudMail settings,
                        String path,
                        Map<String, Object> payload,
                        String token) {
        try {
            RequestBody body = RequestBody.create(objectMapper.writeValueAsBytes(payload), JSON);
            Request.Builder builder = new Request.Builder().url(apiUrl(settings, path)).post(body);
            if (StringUtils.hasText(token)) {
                builder.header("Authorization", token);
            }
            return execute(builder.build());
        } catch (IOException ex) {
            throw new BusinessException(503, "Cloud Mail 请求构造失败");
        }
    }

    private Object execute(Request request) {
        try (Response response = okHttpClient.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            Map<String, Object> envelope = StringUtils.hasText(raw)
                    ? objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() { })
                    : Collections.emptyMap();
            int code = intValue(envelope.get("code"), response.code());
            if (response.code() == 401 || code == 401) {
                throw new CloudMailUnauthorizedException();
            }
            if (!response.isSuccessful() || code != 200) {
                throw new BusinessException(503, "Cloud Mail 发信服务请求失败");
            }
            return envelope.get("data");
        } catch (CloudMailUnauthorizedException | BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException(503, "无法连接 Cloud Mail 发信服务");
        }
    }

    private AppProperties.CloudMail settings() {
        return props.getRegistration().getEmailVerification().getCloudMail();
    }

    private void assertConfigured(AppProperties.CloudMail settings) {
        if (!StringUtils.hasText(settings.getBaseUrl())
                || !StringUtils.hasText(settings.getUsername())
                || !StringUtils.hasText(settings.getPassword())
                || !StringUtils.hasText(settings.getFrom())) {
            throw new BusinessException(503, "Cloud Mail 发信账号尚未配置");
        }
    }

    private String apiUrl(AppProperties.CloudMail settings, String path) {
        String baseUrl = settings.getBaseUrl().trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (!baseUrl.endsWith("/api")) {
            baseUrl += "/api";
        }
        return baseUrl + path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long longValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private int intValue(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static class CloudMailSession {
        private final String token;
        private final long accountId;

        private CloudMailSession(String token, long accountId) {
            this.token = token;
            this.accountId = accountId;
        }
    }

    private static class CloudMailUnauthorizedException extends RuntimeException {
    }
}
