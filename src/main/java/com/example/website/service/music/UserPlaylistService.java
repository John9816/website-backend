package com.example.website.service.music;

import com.example.website.common.BusinessException;
import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.OkHttpConfig;
import com.example.website.dto.PageView;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.PlaylistDetailView;
import com.example.website.dto.music.SongSearchItem;
import com.example.website.dto.music.UserPlaylistDetailView;
import com.example.website.dto.music.UserPlaylistItemView;
import com.example.website.dto.music.UserPlaylistView;
import com.example.website.entity.UserPlaylist;
import com.example.website.entity.UserPlaylistItem;
import com.example.website.repository.UserPlaylistItemRepository;
import com.example.website.repository.UserPlaylistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserPlaylistService {

    private static final int MAX_IMPORT_TRACKS = 1000;
    private static final int IMPORT_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int MAX_PAGE_SIZE = 200;

    private static final Pattern QQ_PATH_ID = Pattern.compile("/playlist/(\\d+)");
    private static final Pattern NUMERIC_ID = Pattern.compile("(\\d+)");

    private final QqMusicClient qq;
    private final NeteaseMusicClient netease;
    private final UserPlaylistRepository playlistRepository;
    private final UserPlaylistItemRepository itemRepository;
    @Qualifier(OkHttpConfig.CLIENT_QUICK)
    private final OkHttpClient okHttpClient;
    private final TransactionTemplate transactionTemplate;

    public UserPlaylistView importFromUrl(Long userId, String rawUrl) {
        String url = requireText(rawUrl, "url is required");
        PlaylistRef ref = parseShareUrl(url);

        playlistRepository.findByUserIdAndSourceAndSourceId(userId, ref.source.getValue(), ref.externalId)
                .ifPresent(p -> { throw new BusinessException(409, "该歌单已导入"); });

        List<SongSearchItem> allSongs = new ArrayList<>();
        PlaylistDetailView firstPage = fetchPage(ref, 1, IMPORT_PAGE_SIZE);
        if (firstPage == null || firstPage.getList() == null) {
            throw new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAYLIST_FAILED,
                    "empty playlist response from " + ref.source.getValue());
        }
        allSongs.addAll(firstPage.getList());

        Integer total = firstPage.getTotal();
        int targetCount = total != null && total > 0
                ? Math.min(total, MAX_IMPORT_TRACKS)
                : MAX_IMPORT_TRACKS;

        int page = 2;
        while (allSongs.size() < targetCount) {
            PlaylistDetailView next = fetchPage(ref, page, IMPORT_PAGE_SIZE);
            if (next == null || next.getList() == null || next.getList().isEmpty()) {
                break;
            }
            allSongs.addAll(next.getList());
            page++;
            if (page > 200) {
                log.warn("Import safety stop after 200 pages for {}/{}", ref.source.getValue(), ref.externalId);
                break;
            }
        }
        if (allSongs.size() > MAX_IMPORT_TRACKS) {
            allSongs = allSongs.subList(0, MAX_IMPORT_TRACKS);
        }

        final List<SongSearchItem> songsToPersist = allSongs;
        final PlaylistDetailView firstPageRef = firstPage;
        final String urlRef = url;
        final PlaylistRef refRef = ref;

        // All upstream HTTP work above is finished; only the writes go inside the transaction
        // so HikariCP connections are not held during multi-second upstream paging.
        return transactionTemplate.execute(status -> {
            UserPlaylist entity = new UserPlaylist();
            entity.setUserId(userId);
            entity.setSource(refRef.source.getValue());
            entity.setSourceId(refRef.externalId);
            entity.setSourceUrl(limit(urlRef, 1000));
            entity.setName(limit(firstNonBlank(firstPageRef.getName(), "Imported Playlist"), 300));
            entity.setCoverUrl(limit(trimmedOrNull(firstPageRef.getCoverUrl()), 1000));
            entity.setDescription(limit(trimmedOrNull(firstPageRef.getDescription()), 2000));
            entity.setCreatorName(limit(trimmedOrNull(firstPageRef.getCreatorName()), 300));
            entity.setTrackCount(songsToPersist.size());
            UserPlaylist saved = playlistRepository.save(entity);

            List<UserPlaylistItem> items = new ArrayList<>(songsToPersist.size());
            for (int i = 0; i < songsToPersist.size(); i++) {
                SongSearchItem song = songsToPersist.get(i);
                String songId = trimmedOrNull(song.getId());
                if (songId == null) continue;
                UserPlaylistItem it = new UserPlaylistItem();
                it.setPlaylistId(saved.getId());
                it.setSource(song.getSource() != null ? song.getSource().getValue() : refRef.source.getValue());
                it.setSongId(limit(songId, 100));
                it.setName(limit(firstNonBlank(song.getName(), songId), 300));
                it.setArtist(limit(trimmedOrNull(song.getArtist()), 300));
                it.setAlbum(limit(trimmedOrNull(song.getAlbum()), 300));
                it.setCoverUrl(limit(trimmedOrNull(song.getCoverUrl()), 1000));
                it.setDurationSec(normalizeDuration(song.getDurationSec()));
                it.setSortOrder(i);
                items.add(it);
            }
            itemRepository.saveAll(items);
            saved.setTrackCount(items.size());
            playlistRepository.save(saved);

            log.info("Imported playlist {}/{} ({}) for user {}: {} tracks",
                    refRef.source.getValue(), refRef.externalId, saved.getName(), userId, items.size());
            return UserPlaylistView.from(saved);
        });
    }

    public PageView<UserPlaylistView> list(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size));
        Page<UserPlaylist> result = playlistRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable);
        return PageView.from(result, UserPlaylistView::from);
    }

    public UserPlaylistDetailView detail(Long userId, Long playlistId, int page, int size) {
        UserPlaylist playlist = requireOwned(userId, playlistId);
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size));
        Page<UserPlaylistItem> items = itemRepository.findByPlaylistIdOrderBySortOrderAscIdAsc(playlist.getId(), pageable);
        return new UserPlaylistDetailView(
                UserPlaylistView.from(playlist),
                PageView.from(items, UserPlaylistItemView::from)
        );
    }

    @Transactional
    public void delete(Long userId, Long playlistId) {
        UserPlaylist playlist = requireOwned(userId, playlistId);
        playlistRepository.delete(playlist);
    }

    @Transactional
    public void removeItem(Long userId, Long playlistId, Long itemId) {
        UserPlaylist playlist = requireOwned(userId, playlistId);
        UserPlaylistItem item = itemRepository.findByIdAndPlaylistId(itemId, playlist.getId())
                .orElseThrow(() -> new BusinessException(404, "Playlist item not found"));
        itemRepository.delete(item);
        long remaining = itemRepository.countByPlaylistId(playlist.getId());
        playlist.setTrackCount((int) remaining);
        playlistRepository.save(playlist);
    }

    @Transactional
    public UserPlaylistView rename(Long userId, Long playlistId, String name) {
        UserPlaylist playlist = requireOwned(userId, playlistId);
        playlist.setName(limit(requireText(name, "name is required"), 300));
        return UserPlaylistView.from(playlistRepository.save(playlist));
    }

    private UserPlaylist requireOwned(Long userId, Long playlistId) {
        return playlistRepository.findByIdAndUserId(playlistId, userId)
                .orElseThrow(() -> new BusinessException(404, "Playlist not found"));
    }

    private PlaylistDetailView fetchPage(PlaylistRef ref, int page, int pageSize) {
        switch (ref.source) {
            case QQ:
                return qq.fetchPlaylistDetail(ref.externalId, page, pageSize);
            case NETEASE:
                return netease.fetchPlaylistDetail(ref.externalId, page, pageSize);
            default:
                throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE,
                        "import not supported for " + ref.source.getValue());
        }
    }

    /**
     * Visible for tests.
     */
    PlaylistRef parseShareUrl(String rawUrl) {
        String url = rawUrl.trim();
        // Strip surrounding noise that mobile apps often include in share text.
        Matcher urlExtract = Pattern.compile("https?://[\\w./?%#=&\\-+:]+").matcher(url);
        if (urlExtract.find()) {
            url = urlExtract.group();
        }

        PlaylistRef ref = matchKnownHost(url);
        if (ref != null) return ref;

        // Short-link: follow redirects and try again on the resolved URL.
        String resolved = followRedirects(url);
        if (resolved != null && !resolved.equals(url)) {
            ref = matchKnownHost(resolved);
            if (ref != null) return ref;
        }

        throw new MusicBusinessException(MusicErrorCode.INVALID_SOURCE,
                "unrecognised playlist url: " + rawUrl);
    }

    private PlaylistRef matchKnownHost(String url) {
        // Move "#" fragment payload (e.g. https://music.163.com/#/playlist?id=123)
        // into the query so HttpUrl can see the params.
        String hashFlattened = url.replaceFirst("#/", "");
        HttpUrl parsed = HttpUrl.parse(hashFlattened);
        if (parsed == null) return null;
        String host = parsed.host().toLowerCase();
        String path = parsed.encodedPath();

        if (host.endsWith("163.com") || host.endsWith("163cn.tv")) {
            String id = parsed.queryParameter("id");
            if (id != null && id.matches("\\d+")) {
                return new PlaylistRef(MusicSource.NETEASE, id);
            }
            Matcher m = Pattern.compile("/playlist/(\\d+)").matcher(path);
            if (m.find()) {
                return new PlaylistRef(MusicSource.NETEASE, m.group(1));
            }
            return null;
        }

        if (host.endsWith("y.qq.com") || host.endsWith("qq.com")) {
            Matcher m = QQ_PATH_ID.matcher(path);
            if (m.find()) {
                return new PlaylistRef(MusicSource.QQ, m.group(1));
            }
            String id = parsed.queryParameter("id");
            if (id != null && id.matches("\\d+")) {
                return new PlaylistRef(MusicSource.QQ, id);
            }
            String diss = parsed.queryParameter("disstid");
            if (diss != null && diss.matches("\\d+")) {
                return new PlaylistRef(MusicSource.QQ, diss);
            }
            return null;
        }

        return null;
    }

    private String followRedirects(String url) {
        if (okHttpClient == null) {
            return null;
        }
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .get()
                    .build();
            try (Response resp = okHttpClient.newCall(req).execute()) {
                HttpUrl finalUrl = resp.request().url();
                return finalUrl == null ? null : finalUrl.toString();
            }
        } catch (IOException | IllegalArgumentException ex) {
            log.debug("Failed to resolve redirect for {}: {}", url, ex.getMessage());
            return null;
        }
    }

    private static String firstNonBlank(String first, String fallback) {
        String v = trimmedOrNull(first);
        return v != null ? v : fallback;
    }

    private static String trimmedOrNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private static String limit(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) return value;
        return value.substring(0, maxLen);
    }

    private static String requireText(String value, String message) {
        String t = trimmedOrNull(value);
        if (t == null) throw new BusinessException(400, message);
        return t;
    }

    private static int normalizeSize(int size) {
        if (size <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static Integer normalizeDuration(Integer durationSec) {
        if (durationSec == null || durationSec <= 0) return null;
        return durationSec;
    }

    static final class PlaylistRef {
        final MusicSource source;
        final String externalId;

        PlaylistRef(MusicSource source, String externalId) {
            this.source = source;
            this.externalId = externalId;
        }
    }
}
