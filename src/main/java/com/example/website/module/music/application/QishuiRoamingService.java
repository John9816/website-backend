package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.*;
import com.github.benmanes.caffeine.cache.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Per-website-user feed state; never placed in the shared music result cache. */
@Service
public class QishuiRoamingService {
    private static final Logger log = LoggerFactory.getLogger(QishuiRoamingService.class);

    /** Cards per upstream request. Bigger pages cut the number of repeat rounds. */
    static final int PAGE_SIZE = 30;
    /** Upstream rounds per page before giving up; each round is one /feed/song-tab/ POST. */
    private static final int MAX_FETCH_ROUNDS = 2;
    /** A full page is one mode lookup plus up to two feed rounds. */
    private static final long PAGE_BUDGET_SECONDS = 12L;
    /** Mode cards are a single cached read, so they get a tighter budget. */
    private static final long MODE_BUDGET_SECONDS = 8L;
    /** Queue instead of failing: a mode-list fetch must never abort a session start. */
    private static final long LOCK_WAIT_SECONDS = 2L;

    private final QishuiFeedClient client;
    private final QishuiCredentialStore store;
    private final Clock clock;
    private final Cache<String, List<QishuiFeedModeView>> modeLists;
    private final Cache<Long, Session> sessions = Caffeine.newBuilder()
            .maximumSize(1000).expireAfterWrite(30, TimeUnit.MINUTES).build();
    private final Cache<Long, Long> lastStarts = Caffeine.newBuilder()
            .maximumSize(10000).expireAfterWrite(10, TimeUnit.SECONDS).build();
    private final ReentrantLock[] locks = new ReentrantLock[64];
    /** Mode cards are read-only, so they get their own pool instead of blocking sessions. */
    private final ReentrantLock[] modeLocks = new ReentrantLock[64];

    @Autowired
    public QishuiRoamingService(QishuiFeedClient client, QishuiCredentialStore store) {
        this(client, store, Clock.systemUTC());
    }
    QishuiRoamingService(QishuiFeedClient client, QishuiCredentialStore store, Clock clock) {
        this(client, store, clock, Ticker.systemTicker());
    }
    QishuiRoamingService(QishuiFeedClient client, QishuiCredentialStore store, Clock clock, Ticker ticker) {
        this.client = client; this.store = store; this.clock = clock;
        this.modeLists = Caffeine.newBuilder().maximumSize(1000)
                .expireAfterWrite(5, TimeUnit.MINUTES).ticker(ticker).build();
        for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock();
        for (int i = 0; i < modeLocks.length; i++) modeLocks[i] = new ReentrantLock();
    }

    public List<QishuiFeedModeView> modes(Long userId) {
        return lockedOn(modeLocks, userId, MODE_BUDGET_SECONDS, () -> {
            QishuiCredentialStore.StoredCredential account = account(userId);
            return copyModes(loadModes(userId, account.getBindingId(), account.getCredential()));
        });
    }

    public QishuiRoamingPageView start(Long userId, String modeId) {
        return locked(userId, () -> {
            QishuiCredentialStore.StoredCredential account = account(userId);
            Long last = lastStarts.getIfPresent(userId);
            if (last != null && clock.millis() - last < 1000)
                throw new BusinessException(429, "Please wait before starting another roaming session");
            lastStarts.put(userId, clock.millis());
            Instant end = Instant.now(clock).plusSeconds(1800);
            if (account.getCredential().getExpiresAt().isBefore(end)) end = account.getCredential().getExpiresAt();
            Session session = new Session(account.getBindingId(), end);
            selectMode(userId, session, normalize(modeId), account.getCredential());
            QishuiRoamingPageView result = fetch(userId, session, account.getCredential(), 0);
            sessions.put(userId, session);
            return result;
        });
    }

    public QishuiRoamingPageView next(Long userId, String sessionId, long sequence, String modeId) {
        return locked(userId, () -> {
            Session s = session(userId, sessionId);
            QishuiCredential credential = current(userId, s.binding).getCredential();
            if (sequence < 1 || sequence > 100000) throw new BusinessException(400, "Invalid roaming sequence");
            String requested = modeId == null ? s.mode : normalize(modeId);
            if (s.last != null && sequence == s.last.getSequence()) {
                if (modeId != null && !requested.equals(s.last.getModeId()))
                    throw new BusinessException(409, "Roaming sequence was already used with a different mode");
                verifyCurrent(userId, s.binding, credential);
                return copyPage(s.last);
            }
            if (s.last == null || sequence != s.last.getNextSequence())
                throw new BusinessException(409, "Use the nextSequence returned by the previous roaming page");
            // Only publish state after a complete successful page. A failed mode switch or
            // upstream request must not consume the caller's sequence or delivered-song set.
            Session working = new Session(s);
            selectMode(userId, working, requested, credential);
            QishuiRoamingPageView result = fetch(userId, working, credential, sequence);
            sessions.put(userId, working);
            return result;
        });
    }

    public void close(Long userId, String sessionId) {
        locked(userId, () -> { session(userId, sessionId); sessions.invalidate(userId); return null; });
    }

    private QishuiRoamingPageView fetch(Long userId, Session s, QishuiCredential c, long sequence) {
        long startedAt = System.nanoTime();
        int upstreamCalls = 0;
        List<SongSearchItem> songs = new ArrayList<>();
        try {
            // One binding check per page is enough: a concurrent rebinding is caught by the
            // next request, and re-reading the row on every round only added DB round trips.
            verifyCurrent(userId, s.binding, c);
            for (int round = 0; round < MAX_FETCH_ROUNDS && s.hasMore && songs.isEmpty(); round++) {
                MusicRequestBudget.ensureRemaining();
                ensureAlive(s);
                checkCredential(userId, c);
                QishuiFeedClient.Page page = client.songs(
                        c, s.mode, ++s.total, ++s.modeCount, s.selectedAt, PAGE_SIZE);
                upstreamCalls++;
                checkCredential(userId, c);
                ensureAlive(s);
                s.hasMore = page.hasMore;
                for (SongSearchItem song : page.songs) {
                    if (songs.size() == PAGE_SIZE) break;
                    if (s.seen.add(song.getId())) songs.add(song);
                }
                while (s.seen.size() > 1000) s.seen.remove(s.seen.iterator().next());
            }
            ensureAlive(s);
            // Close the page with the authoritative check: a rebinding that happened while
            // upstream was responding must still discard the response.
            verifyCurrent(userId, s.binding, c);
            if (songs.isEmpty() && s.hasMore) throw QishuiHttpClient.failure("no_new_roaming_tracks");
            s.last = new QishuiRoamingPageView(s.id, MusicSource.QISHUI, s.mode, sequence,
                    sequence + 1, s.hasMore, s.expiresAt, songs);
            log.info("Qishui roaming page userId={} mode={} sequence={} upstream={} songs={}"
                            + " elapsedMs={} outcome=success",
                    userId, s.mode, sequence, upstreamCalls, songs.size(), elapsedMs(startedAt));
            return copyPage(s.last);
        } catch (RuntimeException ex) {
            log.warn("Qishui roaming page userId={} mode={} sequence={} upstream={} elapsedMs={}"
                            + " outcome=failure reason={}",
                    userId, s.mode, sequence, upstreamCalls, elapsedMs(startedAt),
                    ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private void selectMode(Long userId, Session s, String mode, QishuiCredential c) {
        if (mode.equals(s.mode)) return;
        if (!QishuiFeedClient.RECOMMENDED.equals(mode)) {
            if (s.allowedModes == null) {
                s.allowedModes = new HashSet<>();
                for (QishuiFeedModeView item : loadModes(userId, s.binding, c)) s.allowedModes.add(item.getId());
            }
            if (!s.allowedModes.contains(mode)) throw new BusinessException(400, "Unknown Qishui roaming mode");
        }
        s.mode = mode; s.modeCount = 0; s.hasMore = true;
        s.selectedAt = QishuiFeedClient.RECOMMENDED.equals(mode) ? null : Instant.now(clock).getEpochSecond();
    }

    private List<QishuiFeedModeView> loadModes(Long userId, String binding, QishuiCredential credential) {
        String key = userId + ":" + binding;
        List<QishuiFeedModeView> modes = modeLists.getIfPresent(key);
        if (modes != null) {
            verifyCurrent(userId, binding, credential);
            return modes;
        }
        modes = client.modes(credential);
        verifyCurrent(userId, binding, credential);
        modeLists.put(key, modes);
        return modes;
    }

    private static List<QishuiFeedModeView> copyModes(List<QishuiFeedModeView> modes) {
        List<QishuiFeedModeView> copy = new ArrayList<>();
        for (QishuiFeedModeView mode : modes)
            copy.add(new QishuiFeedModeView(mode.getId(), mode.getName(), mode.getGroup(), mode.getIconUrl(), mode.getToast()));
        return copy;
    }

    private Session session(Long userId, String id) {
        Session s = sessions.getIfPresent(userId);
        if (s == null || !s.id.equals(id))
            throw new BusinessException(404, "Roaming session not found or expired; start a new session");
        ensureAlive(s);
        return s;
    }

    private void ensureAlive(Session s) {
        if (!s.expiresAt.isAfter(Instant.now(clock)))
            throw new BusinessException(404, "Roaming session not found or expired; start a new session");
    }

    private static QishuiRoamingPageView copyPage(QishuiRoamingPageView page) {
        return new QishuiRoamingPageView(page.getSessionId(), page.getSource(), page.getModeId(),
                page.getSequence(), page.getNextSequence(), page.isHasMore(), page.getExpiresAt(),
                QishuiFeedClient.copySongs(page.getList()));
    }

    private QishuiCredentialStore.StoredCredential current(Long userId, String binding) {
        QishuiCredentialStore.StoredCredential saved = account(userId);
        if (!binding.equals(saved.getBindingId())) {
            sessions.invalidate(userId);
            throw new BusinessException(409, "Qishui binding changed; start a new roaming session");
        }
        return saved;
    }

    private void verifyCurrent(Long userId, String binding, QishuiCredential credential) {
        checkCredential(userId, credential);
        if (!store.isCurrent(userId, binding)) {
            sessions.invalidate(userId);
            modeLists.invalidate(userId + ":" + binding);
            throw new BusinessException(409, "Qishui binding changed; start a new roaming session");
        }
    }

    private QishuiCredentialStore.StoredCredential account(Long userId) {
        QishuiCredentialStore.StoredCredential saved = store.load(userId)
                .orElseThrow(() -> new BusinessException(409, "Bind a Qishui account before roaming"));
        QishuiCredential c = saved.getCredential();
        checkCredential(userId, c);
        return saved;
    }

    private void checkCredential(Long userId, QishuiCredential c) {
        if (c.getExpiresAt() == null || !c.getExpiresAt().isAfter(Instant.now(clock))) {
            sessions.invalidate(userId);
            throw new BusinessException(409, "Qishui authorization expired; refresh or authorize again");
        }
        if (c.getUserId() == null || c.getUserId().isEmpty() || c.getDeviceId() == null || c.getDeviceId().isEmpty())
            throw new BusinessException(409, "Qishui account identity is incomplete; authorize again");
    }

    private static String normalize(String mode) {
        if (mode == null || mode.trim().isEmpty()) return QishuiFeedClient.RECOMMENDED;
        String value = mode.trim();
        if (!QishuiFeedClient.RECOMMENDED.equals(value) && !value.matches("[0-9]{1,9}"))
            throw new BusinessException(400, "Invalid Qishui roaming mode");
        return value;
    }

    private <T> T locked(Long userId, Supplier<T> action) {
        return lockedOn(locks, userId, PAGE_BUDGET_SECONDS, action);
    }

    private <T> T lockedOn(ReentrantLock[] pool, Long userId, long budgetSeconds, Supplier<T> action) {
        if (userId == null) throw new BusinessException(401, "Authentication required");
        ReentrantLock lock = pool[Math.floorMod(userId.hashCode(), pool.length)];
        // Queue briefly instead of failing outright: mode cards and session starts for the
        // same user arrive together from the client, and a lost tryLock surfaced as a 503.
        if (!acquire(lock)) throw new MusicBusinessException(MusicErrorCode.SERVICE_BUSY);
        try (MusicRequestBudget.Scope ignored =
                MusicRequestBudget.start(budgetSeconds, TimeUnit.SECONDS)) {
            return action.get();
        } finally { lock.unlock(); }
    }

    private static boolean acquire(ReentrantLock lock) {
        try {
            return lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final class Session {
        final String id;
        final String binding;
        final Instant expiresAt;
        final Set<String> seen = new LinkedHashSet<>();
        Set<String> allowedModes;
        String mode;
        Long selectedAt;
        long total, modeCount;
        boolean hasMore = true;
        QishuiRoamingPageView last;
        Session(String binding, Instant expiresAt) {
            this.id = UUID.randomUUID().toString(); this.binding = binding; this.expiresAt = expiresAt;
        }
        Session(Session previous) {
            this.id = previous.id; this.binding = previous.binding; this.expiresAt = previous.expiresAt;
            this.seen.addAll(previous.seen);
            this.allowedModes = previous.allowedModes == null ? null : new HashSet<>(previous.allowedModes);
            this.mode = previous.mode; this.selectedAt = previous.selectedAt;
            this.total = previous.total; this.modeCount = previous.modeCount;
            this.hasMore = previous.hasMore; this.last = previous.last;
        }
    }
}
