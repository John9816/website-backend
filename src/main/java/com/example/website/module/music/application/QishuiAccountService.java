package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;

import com.example.website.module.music.api.dto.QishuiAccountView;
import com.fasterxml.jackson.databind.*;
import com.github.benmanes.caffeine.cache.*;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
public class QishuiAccountService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QishuiAccountService.class);
    private final java.time.Clock clock;
    private final QishuiAuthClient client;
    private final QishuiCredentialStore store;
    private final Cache<Long, Session> sessions =
            Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(3, TimeUnit.MINUTES).build();
    private final Cache<Long, Long> lastStarts =
            Caffeine.newBuilder().maximumSize(10000).expireAfterWrite(10, TimeUnit.SECONDS).build();
    private final ReentrantLock[] locks = new ReentrantLock[64];
    private final QishuiAuthAutoRefreshService autoRefresh;

    @org.springframework.beans.factory.annotation.Autowired
    public QishuiAccountService(
            QishuiAuthClient client,
            QishuiCredentialStore store,
            ObjectMapper mapper,
            QishuiAuthAutoRefreshService autoRefresh) {
        this(client, store, java.time.Clock.systemUTC(), autoRefresh);
    }

    /** Test constructor: no upstream auto-refresh, so account views stay offline. */
    QishuiAccountService(
            QishuiAuthClient client, QishuiCredentialStore store, ObjectMapper mapper) {
        this(client, store, java.time.Clock.systemUTC(), null);
    }

    QishuiAccountService(
            QishuiAuthClient client, QishuiCredentialStore store, java.time.Clock clock) {
        this(client, store, clock, null);
    }

    QishuiAccountService(
            QishuiAuthClient client,
            QishuiCredentialStore store,
            java.time.Clock clock,
            QishuiAuthAutoRefreshService autoRefresh) {
        this.client = client;
        this.store = store;
        this.clock = clock;
        this.autoRefresh = autoRefresh;
        for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock();
    }

    public QishuiAccountView start(Long userId) {
        return locked(
                userId,
                () -> {
                    store.requireConfigured();
                    // A replacement login is allowed; upstream creation remains bounded per user.
                    Long last = lastStarts.getIfPresent(userId);
                    if (last != null && clock.millis() - last < 1000)
                        throw new BusinessException(
                                429, "Please wait before creating another Qishui login");
                    lastStarts.put(userId, clock.millis());
                    String expectedBinding =
                            store.load(userId)
                                    .map(QishuiCredentialStore.StoredCredential::getBindingId)
                                    .orElse(null);
                    QishuiAuthClient.LoginStart start = client.start();
                    Session s =
                            new Session(
                                    start, expectedBinding, Instant.now(clock).plusSeconds(180));
                    sessions.put(userId, s);
                    return view(s, true);
                });
    }

    public QishuiAccountView poll(Long userId, String sessionId) {
        return locked(
                userId,
                () -> {
                    Session s = sessions.getIfPresent(userId);
                    if (s == null || !s.id.equals(sessionId))
                        throw new BusinessException(
                                404, "Qishui login session not found or expired");
                    if (!s.expiresAt.isAfter(Instant.now(clock))) {
                        sessions.invalidate(userId);
                        throw new BusinessException(404, "Qishui login session expired");
                    }
                    if (!"new".equals(s.status) && !"scanned".equals(s.status))
                        return view(s, false);
                    if (s.credential != null) {
                        persist(userId, s);
                        return view(s, false);
                    }
                    if (clock.millis() < s.nextPoll) return view(s, false);
                    s.nextPoll = clock.millis() + 2000;
                    JsonNode d = client.poll(s.start.token);
                    String status = d.path("status").asText("");
                    if (!Arrays.asList("new", "scanned", "confirmed", "refused", "expired")
                            .contains(status)) {
                        log.warn("Qishui auth invalid poll status; statusPresent={}", d.hasNonNull("status"));
                        throw QishuiHttpClient.failure();
                    }
                    if ("confirmed".equals(status)) {
                        String code = d.path("code").asText(d.path("auth_code").asText(""));
                        if (code.isEmpty()) {
                            log.warn("Qishui auth confirmation missing code; codePresent={} authCodePresent={}",
                                    d.hasNonNull("code"), d.hasNonNull("auth_code"));
                            throw QishuiHttpClient.failure();
                        }
                        // Retain completed exchange on a storage failure; never replay a consumed
                        // authorization code.
                        try {
                            s.credential = client.complete(code, s.deviceId, s.guard);
                        } catch (RuntimeException ex) {
                            s.status = "failed";
                            s.start = null;
                            s.guard = null;
                            throw ex;
                        }
                        persist(userId, s);
                    } else s.status = status;
                    return view(s, false);
                });
    }

    public QishuiAccountView account(Long userId) {
        // Lazily renew a nearly-expired access code so the user does not have to press
        // "refresh authorization" every six hours. Best-effort: a failed renewal simply
        // falls through to the unchanged view, which still shows the real expiry status.
        if (autoRefresh != null) autoRefresh.refreshIfExpiring(userId);
        return store.load(userId)
                .map(s -> accountView(s.getCredential()))
                .orElseGet(
                        () -> {
                            QishuiAccountView v = new QishuiAccountView();
                            v.setStatus("unbound");
                            return v;
                        });
    }

    public QishuiAccountView refresh(Long userId) {
        return locked(
                userId,
                () -> {
                    QishuiCredentialStore.StoredCredential saved =
                            store.load(userId)
                                    .orElseThrow(
                                            () ->
                                                    new BusinessException(
                                                            409, "Qishui account is not bound"));
                    QishuiCredential updated = client.refresh(saved.getCredential());
                    if (!store.replaceIfCurrent(userId, saved.getBindingId(), updated))
                        throw new BusinessException(
                                409, "Qishui binding changed; reload account status");
                    return accountView(updated);
                });
    }

    public void delete(Long userId) {
        locked(
                userId,
                () -> {
                    sessions.invalidate(userId);
                    store.delete(userId);
                    return null;
                });
    }

    private <T> T locked(Long userId, Supplier<T> operation) {
        Objects.requireNonNull(userId);
        ReentrantLock lock = locks[Math.floorMod(userId.hashCode(), locks.length)];
        if (!lock.tryLock()) throw new MusicBusinessException(MusicErrorCode.SERVICE_BUSY);
        try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(15, TimeUnit.SECONDS)) {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    private void persist(Long userId, Session s) {
        if (!store.bindIfCurrent(userId, s.expectedBinding, s.credential)) {
            sessions.invalidate(userId);
            throw new BusinessException(409, "Qishui binding changed; create a new login");
        }
        s.status = "confirmed";
        s.start = null;
        s.guard = null;
    }

    private QishuiAccountView view(Session s, boolean includeQr) {
        QishuiAccountView v =
                "confirmed".equals(s.status) ? accountView(s.credential) : new QishuiAccountView();
        v.setStatus(s.status);
        v.setSessionId(s.id);
        v.setSessionExpiresAt(s.expiresAt);
        v.setPollIntervalSeconds(2);
        if (includeQr) {
            v.setQrcode(s.start.qrcode);
            v.setQrcodeUrl(s.start.qrcodeUrl);
        }
        return v;
    }

    private QishuiAccountView accountView(QishuiCredential c) {
        QishuiAccountView v = new QishuiAccountView();
        v.setBound(true);
        v.setExpiresAt(c.getExpiresAt());
        v.setStatus(
                c.getExpiresAt() != null && c.getExpiresAt().isAfter(Instant.now(clock))
                        ? "bound"
                        : "expired");
        if (c.getProfile() != null) {
            v.setNickname(c.getProfile().getNickname());
            v.setAvatarUrl(c.getProfile().getAvatarUrl());
            v.setVip(c.getProfile().getIsVip());
        }
        return v;
    }

    private static final class Session {
        final String expectedBinding;
        final String id = UUID.randomUUID().toString(),
                deviceId = UUID.randomUUID().toString().replace("-", "");
        final Instant expiresAt;
        QishuiGuard guard = QishuiGuard.create();
        QishuiAuthClient.LoginStart start;
        String status = "new";
        long nextPoll;
        QishuiCredential credential;

        Session(QishuiAuthClient.LoginStart start, String expectedBinding, Instant expiresAt) {
            this.start = start;
            this.expectedBinding = expectedBinding;
            this.expiresAt = expiresAt;
        }
    }
}
