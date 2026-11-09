package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.*;
import com.github.benmanes.caffeine.cache.*;
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
    private final QishuiFeedClient client;
    private final QishuiCredentialStore store;
    private final Clock clock;
    private final Cache<Long, Session> sessions = Caffeine.newBuilder()
            .maximumSize(1000).expireAfterWrite(30, TimeUnit.MINUTES).build();
    private final Cache<Long, Long> lastStarts = Caffeine.newBuilder()
            .maximumSize(10000).expireAfterWrite(10, TimeUnit.SECONDS).build();
    private final ReentrantLock[] locks = new ReentrantLock[64];

    @Autowired
    public QishuiRoamingService(QishuiFeedClient client, QishuiCredentialStore store) {
        this(client, store, Clock.systemUTC());
    }
    QishuiRoamingService(QishuiFeedClient client, QishuiCredentialStore store, Clock clock) {
        this.client = client; this.store = store; this.clock = clock;
        for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock();
    }

    public List<QishuiFeedModeView> modes(Long userId) {
        return locked(userId, () -> {
            QishuiCredentialStore.StoredCredential account = account(userId);
            List<QishuiFeedModeView> modes = client.modes(account.getCredential());
            current(userId, account.getBindingId());
            return modes;
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
            selectMode(session, normalize(modeId), account.getCredential());
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
                return copyPage(s.last);
            }
            if (s.last == null || sequence != s.last.getNextSequence())
                throw new BusinessException(409, "Use the nextSequence returned by the previous roaming page");
            // Only publish state after a complete successful page. A failed mode switch or
            // upstream request must not consume the caller's sequence or delivered-song set.
            Session working = new Session(s);
            selectMode(working, requested, credential);
            QishuiRoamingPageView result = fetch(userId, working, credential, sequence);
            sessions.put(userId, working);
            return result;
        });
    }

    public void close(Long userId, String sessionId) {
        locked(userId, () -> { session(userId, sessionId); sessions.invalidate(userId); return null; });
    }

    private QishuiRoamingPageView fetch(Long userId, Session s, QishuiCredential c, long sequence) {
        List<SongSearchItem> songs = new ArrayList<>();
        for (int attempt = 0; attempt < 3 && s.hasMore && songs.isEmpty(); attempt++) {
            MusicRequestBudget.ensureRemaining();
            ensureAlive(s);
            current(userId, s.binding);
            QishuiFeedClient.Page page = client.songs(c, s.mode, ++s.total, ++s.modeCount, s.selectedAt);
            current(userId, s.binding);
            ensureAlive(s);
            s.hasMore = page.hasMore;
            for (SongSearchItem song : page.songs) {
                if (songs.size() == 20) break;
                if (s.seen.add(song.getId())) songs.add(song);
            }
            while (s.seen.size() > 1000) s.seen.remove(s.seen.iterator().next());
        }
        current(userId, s.binding);
        ensureAlive(s);
        if (songs.isEmpty() && s.hasMore) throw QishuiHttpClient.failure("no_new_roaming_tracks");
        s.last = new QishuiRoamingPageView(s.id, MusicSource.QISHUI, s.mode, sequence,
                sequence + 1, s.hasMore, s.expiresAt, songs);
        return copyPage(s.last);
    }

    private void selectMode(Session s, String mode, QishuiCredential c) {
        if (mode.equals(s.mode)) return;
        if (!QishuiFeedClient.RECOMMENDED.equals(mode)) {
            if (s.allowedModes == null) {
                s.allowedModes = new HashSet<>();
                for (QishuiFeedModeView item : client.modes(c)) s.allowedModes.add(item.getId());
            }
            if (!s.allowedModes.contains(mode)) throw new BusinessException(400, "Unknown Qishui roaming mode");
        }
        s.mode = mode; s.modeCount = 0; s.hasMore = true;
        s.selectedAt = QishuiFeedClient.RECOMMENDED.equals(mode) ? null : Instant.now(clock).getEpochSecond();
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
        List<SongSearchItem> songs = new ArrayList<>();
        for (SongSearchItem s : page.getList()) {
            songs.add(new SongSearchItem(s.getId(), s.getSource(), s.getName(), s.getArtist(),
                    s.getArtistId(), s.getArtistIds() == null ? null : new ArrayList<>(s.getArtistIds()),
                    s.getAlbum(), s.getAlbumId(), s.getCoverUrl(), s.getDurationMs(), s.getDurationSec(),
                    s.getAvailableQualities() == null ? null : new ArrayList<>(s.getAvailableQualities())));
        }
        return new QishuiRoamingPageView(page.getSessionId(), page.getSource(), page.getModeId(),
                page.getSequence(), page.getNextSequence(), page.isHasMore(), page.getExpiresAt(), songs);
    }

    private QishuiCredentialStore.StoredCredential current(Long userId, String binding) {
        QishuiCredentialStore.StoredCredential saved = account(userId);
        if (!binding.equals(saved.getBindingId())) {
            sessions.invalidate(userId);
            throw new BusinessException(409, "Qishui binding changed; start a new roaming session");
        }
        return saved;
    }

    private QishuiCredentialStore.StoredCredential account(Long userId) {
        QishuiCredentialStore.StoredCredential saved = store.load(userId)
                .orElseThrow(() -> new BusinessException(409, "Bind a Qishui account before roaming"));
        QishuiCredential c = saved.getCredential();
        if (c.getExpiresAt() == null || !c.getExpiresAt().isAfter(Instant.now(clock))) {
            sessions.invalidate(userId);
            throw new BusinessException(409, "Qishui authorization expired; refresh or authorize again");
        }
        if (c.getUserId() == null || c.getUserId().isEmpty() || c.getDeviceId() == null || c.getDeviceId().isEmpty())
            throw new BusinessException(409, "Qishui account identity is incomplete; authorize again");
        return saved;
    }

    private static String normalize(String mode) {
        if (mode == null || mode.trim().isEmpty()) return QishuiFeedClient.RECOMMENDED;
        String value = mode.trim();
        if (!QishuiFeedClient.RECOMMENDED.equals(value) && !value.matches("[0-9]{1,9}"))
            throw new BusinessException(400, "Invalid Qishui roaming mode");
        return value;
    }

    private <T> T locked(Long userId, Supplier<T> action) {
        if (userId == null) throw new BusinessException(401, "Authentication required");
        ReentrantLock lock = locks[Math.floorMod(userId.hashCode(), locks.length)];
        if (!lock.tryLock()) throw new MusicBusinessException(MusicErrorCode.SERVICE_BUSY);
        try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(8, TimeUnit.SECONDS)) {
            return action.get();
        } finally { lock.unlock(); }
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
