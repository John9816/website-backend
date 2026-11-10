package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.*;
import com.example.website.shared.concurrent.PerformanceSingleFlight;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** TuneFreeNext 4.3.5 feed protocol. Only catalog tokens and server-owned account identities. */
@Component
public class QishuiFeedClient {
    static final String RECOMMENDED = "recommended";
    private final QishuiMusicClient catalog;
    private final ObjectMapper mapper;
    private final Cache<String, Page> discoveryCache;
    private final PerformanceSingleFlight discoveryFlights = new PerformanceSingleFlight();

    @Autowired
    public QishuiFeedClient(QishuiMusicClient catalog, ObjectMapper mapper) {
        this(catalog, mapper, Ticker.systemTicker());
    }

    QishuiFeedClient(QishuiMusicClient catalog, ObjectMapper mapper, Ticker ticker) {
        this.catalog = catalog; this.mapper = mapper;
        this.discoveryCache = Caffeine.newBuilder().maximumSize(1)
                .expireAfterWrite(30, TimeUnit.SECONDS).ticker(ticker).build();
    }

    public QishuiDiscoveryView discovery() {
        try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(8, TimeUnit.SECONDS)) {
            MusicRequestBudget.ensureRemaining();
            Page page = discoveryCache.getIfPresent(RECOMMENDED);
            if (page == null) page = discoveryFlights.execute(RECOMMENDED, () -> {
                Page ready = discoveryCache.getIfPresent(RECOMMENDED);
                if (ready != null) return ready;
                Page parsed = parse(catalog.postFeed("/feed/song-tab/", body(12, RECOMMENDED, 1, 1, null), null));
                Page loaded = new Page(new ArrayList<>(parsed.songs.subList(0, Math.min(12, parsed.songs.size()))), parsed.hasMore);
                MusicRequestBudget.ensureRemaining();
                discoveryCache.put(RECOMMENDED, loaded);
                return loaded;
            }, MusicRequestBudget.remainingNanos(), TimeUnit.NANOSECONDS);
            return new QishuiDiscoveryView(MusicSource.QISHUI,
                    copySongs(page.songs.subList(0, Math.min(12, page.songs.size()))));
        } catch (PerformanceSingleFlight.WaitTimeoutException ex) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT);
        }
    }

    List<QishuiFeedModeView> modes(QishuiCredential credential) {
        JsonNode blocks = catalog.postFeed("/feed/mode/", mapper.createObjectNode(), credential)
                .path("feed_mode_block");
        if (!blocks.isArray()) throw QishuiHttpClient.failure("invalid_feed_modes");
        Map<String, QishuiFeedModeView> modes = new LinkedHashMap<>();
        modes.put(RECOMMENDED, new QishuiFeedModeView(RECOMMENDED, "为你推荐", "推荐", null, "已切换到为你推荐"));
        for (JsonNode block : blocks) {
            if (!block.path("feed_mode").isArray()) continue;
            for (JsonNode card : block.path("feed_mode")) {
                JsonNode mode = card.path("entity").path("feed_mode");
                String id = mode.path("scene_mode").path("scene_mode_id").asText("");
                String name = mode.path("text").asText("").trim();
                if (!"feed_scene_mode".equals(mode.path("type").asText())
                        || !id.matches("[0-9]{1,9}") || name.isEmpty()) continue;
                modes.putIfAbsent(id, new QishuiFeedModeView(id, name,
                        block.path("title").asText("音乐模式"),
                        QishuiMusicClient.imageUrl(mode.path("url_info")),
                        mode.path("cutover_toast").asText(null)));
            }
        }
        return new ArrayList<>(modes.values());
    }

    Page songs(QishuiCredential credential, String mode, long total, long modeCount, Long selectedAt) {
        if (credential == null) throw new IllegalArgumentException("Personal feed requires a binding");
        return parse(catalog.postFeed("/feed/song-tab/", body(20, mode, total, modeCount, selectedAt), credential));
    }

    private ObjectNode body(int count, String mode, long total, long modeCount, Long selectedAt) {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("played_media");
        body.put("is_first_request", modeCount == 1);
        body.put("with_client_cache", modeCount != 1);
        // This name is counterintuitive, but matches the installed client's initial false -> true flag.
        body.put("is_did_first_request", total != 1);
        body.put("count", count);
        ObjectNode preference = body.putObject("feed_preference");
        preference.putNull("preference_mode");
        preference.put("total_request_count", total);
        preference.put("preference_mode_request_count", modeCount);
        preference.put("current_mode_request_count", modeCount);
        if (RECOMMENDED.equals(mode)) {
            preference.putNull("scene_mode_id");
            preference.putNull("scene_mode_select_time");
        } else {
            if (mode == null || !mode.matches("[0-9]{1,9}") || selectedAt == null)
                throw new BusinessException(400, "Invalid Qishui roaming mode");
            preference.put("scene_mode_id", Integer.parseInt(mode));
            preference.put("scene_mode_select_time", selectedAt.longValue());
        }
        return body;
    }

    private Page parse(JsonNode data) {
        if (!data.path("items").isArray() || !data.path("has_more").isBoolean())
            throw QishuiHttpClient.failure("invalid_feed_page");
        Map<String, SongSearchItem> songs = new LinkedHashMap<>();
        for (JsonNode card : data.path("items")) {
            JsonNode media = card.path("entity").path("media");
            if (!"track".equals(card.path("type").asText())
                    || !"track".equals(media.path("media_type").asText())) continue;
            SongSearchItem song = catalog.catalogTrack(media.path("track_entity"));
            if (song != null) songs.putIfAbsent(song.getId(), song);
        }
        return new Page(new ArrayList<>(songs.values()), data.path("has_more").booleanValue());
    }

    static List<SongSearchItem> copySongs(List<SongSearchItem> originals) {
        List<SongSearchItem> songs = new ArrayList<>();
        for (SongSearchItem s : originals) {
            songs.add(new SongSearchItem(s.getId(), s.getSource(), s.getName(), s.getArtist(),
                    s.getArtistId(), s.getArtistIds() == null ? null : new ArrayList<>(s.getArtistIds()),
                    s.getAlbum(), s.getAlbumId(), s.getCoverUrl(), s.getDurationMs(), s.getDurationSec(),
                    s.getAvailableQualities() == null ? null : new ArrayList<>(s.getAvailableQualities())));
        }
        return songs;
    }

    static final class Page {
        final List<SongSearchItem> songs;
        final boolean hasMore;
        Page(List<SongSearchItem> songs, boolean hasMore) { this.songs = songs; this.hasMore = hasMore; }
    }
}
