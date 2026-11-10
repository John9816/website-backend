package com.example.website.module.music.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps the personal Qishui access code alive without a manual "refresh authorization".
 *
 * <p>Upstream issues access codes with expires_time defaulting to 21600 s (6 h). The
 * /access/code/refresh/ chain already exists in QishuiAuthClient; what was missing was any
 * automatic trigger. Refreshing rotates the stored bindingId, which invalidates an
 * in-flight roaming session, so both the lazy path and the scheduled sweep skip users with
 * a live roaming session. All failures are best-effort and never surface to the caller.</p>
 */
@Service
public class QishuiAuthAutoRefreshService {
    private static final Logger log = LoggerFactory.getLogger(QishuiAuthAutoRefreshService.class);

    private final QishuiCredentialStore store;
    private final QishuiAuthClient auth;
    private final QishuiRoamingService roaming;
    private final Clock clock;
    private final long thresholdSec;
    private final boolean enabled;
    private final ReentrantLock[] locks = new ReentrantLock[64];

    @org.springframework.beans.factory.annotation.Autowired
    public QishuiAuthAutoRefreshService(
            QishuiCredentialStore store,
            QishuiAuthClient auth,
            QishuiRoamingService roaming,
            @Value("${app.music.qishui.auth-refresh-threshold-sec:1800}") long thresholdSec,
            @Value("${app.music.qishui.auth-refresh-enabled:true}") boolean enabled) {
        this(store, auth, roaming, Clock.systemUTC(), thresholdSec, enabled);
    }

    QishuiAuthAutoRefreshService(
            QishuiCredentialStore store,
            QishuiAuthClient auth,
            QishuiRoamingService roaming,
            Clock clock,
            long thresholdSec,
            boolean enabled) {
        this.store = store;
        this.auth = auth;
        this.roaming = roaming;
        this.clock = clock;
        this.thresholdSec = thresholdSec;
        this.enabled = enabled;
        for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock();
    }

    boolean needsRefresh(QishuiCredential credential) {
        Instant expiresAt = credential == null ? null : credential.getExpiresAt();
        if (expiresAt == null) return true;
        return !expiresAt.isAfter(Instant.now(clock).plusSeconds(Math.max(0L, thresholdSec)));
    }

    /**
     * Best-effort lazy renewal, invoked before an account view and by the scheduled sweep.
     * Never throws: a failed upstream call must leave the real expiry status visible instead
     * of breaking the request.
     */
    public boolean refreshIfExpiring(Long userId) {
        if (!enabled || userId == null) return false;
        ReentrantLock lock = locks[Math.floorMod(userId.hashCode(), locks.length)];
        if (!lock.tryLock()) return false; // another refresh for this user is already running
        try {
            Optional<QishuiCredentialStore.StoredCredential> saved = store.load(userId);
            if (!saved.isPresent() || !needsRefresh(saved.get().getCredential())) return false;
            if (roaming != null && roaming.hasActiveSession(userId)) {
                log.debug("Qishui auth auto-refresh skipped; live roaming session userId={}", userId);
                return false;
            }
            // QishuiHttpClient constrains every upstream call against the request budget.
            // The scheduler thread has no ambient budget, so start an explicit one.
            try (MusicRequestBudget.Scope ignored =
                    MusicRequestBudget.start(20, TimeUnit.SECONDS)) {
                QishuiCredential updated = auth.refresh(saved.get().getCredential());
                if (!store.replaceIfCurrent(userId, saved.get().getBindingId(), updated)) {
                    log.info("Qishui auth auto-refresh skipped; binding changed userId={}", userId);
                    return false;
                }
            }
            log.info("Qishui auth auto-refreshed userId={}", userId);
            return true;
        } catch (RuntimeException ex) {
            // Never log exception messages: upstream errors can contain credential material.
            log.warn("Qishui auth auto-refresh failed userId={} reason={}",
                    userId, ex.getClass().getSimpleName());
            return false;
        } finally {
            lock.unlock();
        }
    }

    @Scheduled(
            fixedDelayString = "${app.music.qishui.auth-refresh-interval-ms:300000}",
            initialDelayString = "${app.music.qishui.auth-refresh-initial-delay-ms:60000}")
    public void refreshExpiringAccounts() {
        if (!enabled) return;
        List<Long> userIds;
        try {
            userIds = store.allUserIds();
        } catch (RuntimeException ex) {
            log.warn("Qishui auth auto-refresh sweep could not list accounts reason={}",
                    ex.getClass().getSimpleName());
            return;
        }
        if (userIds == null || userIds.isEmpty()) return;
        for (Long userId : userIds) refreshIfExpiring(userId);
        log.debug("Qishui auth auto-refresh sweep finished users={}", userIds.size());
    }
}
