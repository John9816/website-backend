package com.example.website.service.music;

import com.example.website.common.BusinessException;
import com.example.website.dto.PageView;
import com.example.website.dto.music.MusicFavoriteRequest;
import com.example.website.dto.music.MusicFavoriteStatusView;
import com.example.website.dto.music.MusicFavoriteView;
import com.example.website.dto.music.MusicHistoryView;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.PlayInfo;
import com.example.website.entity.MusicFavorite;
import com.example.website.entity.MusicPlayHistory;
import com.example.website.repository.MusicFavoriteRepository;
import com.example.website.repository.MusicPlayHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MusicLibraryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_HISTORY_SIZE = 100;

    private final MusicPlayHistoryRepository historyRepository;
    private final MusicFavoriteRepository favoriteRepository;

    @Transactional
    public void recordPlay(Long userId, PlayInfo info) {
        if (userId == null || info == null || info.getSource() == null) {
            return;
        }
        String songId = limit(trimmedOrNull(info.getId()), 100);
        if (songId == null) {
            return;
        }

        String source = info.getSource().getValue();
        MusicPlayHistory entity = historyRepository.findByUserIdAndSourceAndSongId(userId, source, songId)
                .orElseGet(MusicPlayHistory::new);
        if (entity.getId() == null) {
            entity.setUserId(userId);
            entity.setSource(source);
            entity.setSongId(songId);
        }
        applyTrackFields(entity,
                firstNonBlank(info.getName(), songId),
                info.getArtist(),
                info.getAlbum(),
                info.getCoverUrl(),
                info.getDurationSec());
        entity.setPlayedAt(LocalDateTime.now());
        historyRepository.save(entity);
        trimHistory(userId);
    }

    public PageView<MusicHistoryView> listHistory(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size));
        Page<MusicPlayHistory> result = historyRepository.findByUserIdOrderByPlayedAtDescIdDesc(userId, pageable);
        return PageView.from(result, MusicHistoryView::from);
    }

    @Transactional
    public void deleteHistory(Long userId, Long id) {
        MusicPlayHistory entity = historyRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Music history not found"));
        historyRepository.delete(entity);
    }

    public PageView<MusicFavoriteView> listFavorites(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size));
        Page<MusicFavorite> result = favoriteRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable);
        return PageView.from(result, MusicFavoriteView::from);
    }

    @Transactional
    public MusicFavoriteView saveFavorite(Long userId, MusicFavoriteRequest req) {
        MusicSource source = MusicSource.of(req.getSource());
        String songId = limit(requireText(req.getSongId(), "songId is required"), 100);

        MusicFavorite entity = favoriteRepository.findByUserIdAndSourceAndSongId(userId, source.getValue(), songId)
                .orElseGet(MusicFavorite::new);
        if (entity.getId() == null) {
            entity.setUserId(userId);
            entity.setSource(source.getValue());
            entity.setSongId(songId);
        }
        applyTrackFields(entity,
                requireText(req.getName(), "name is required"),
                req.getArtist(),
                req.getAlbum(),
                req.getCoverUrl(),
                req.getDurationSec());
        return MusicFavoriteView.from(favoriteRepository.save(entity));
    }

    @Transactional
    public void deleteFavorite(Long userId, String rawSource, String rawSongId) {
        MusicSource source = MusicSource.of(rawSource);
        String songId = requireText(rawSongId, "songId is required");
        MusicFavorite entity = favoriteRepository.findByUserIdAndSourceAndSongId(userId, source.getValue(), songId)
                .orElseThrow(() -> new BusinessException(404, "Favorite music not found"));
        favoriteRepository.delete(entity);
    }

    public MusicFavoriteStatusView favoriteStatus(Long userId, String rawSource, String rawSongId) {
        MusicSource source = MusicSource.of(rawSource);
        String songId = requireText(rawSongId, "songId is required");
        return favoriteRepository.findByUserIdAndSourceAndSongId(userId, source.getValue(), songId)
                .map(MusicFavoriteStatusView::liked)
                .orElseGet(() -> MusicFavoriteStatusView.notLiked(source.getValue(), songId));
    }

    public List<MusicFavoriteStatusView> batchFavoriteStatus(Long userId, String rawSource, List<String> rawSongIds) {
        if (rawSongIds == null || rawSongIds.isEmpty()) {
            return Collections.emptyList();
        }
        MusicSource source = MusicSource.of(rawSource);
        List<String> songIds = rawSongIds.stream()
                .map(s -> limit(trimmedOrNull(s), 100))
                .filter(s -> s != null)
                .distinct()
                .collect(Collectors.toList());
        if (songIds.isEmpty()) return Collections.emptyList();

        Map<String, MusicFavorite> liked = favoriteRepository
                .findByUserIdAndSourceAndSongIdIn(userId, source.getValue(), songIds)
                .stream()
                .collect(Collectors.toMap(MusicFavorite::getSongId, f -> f));

        return songIds.stream()
                .map(id -> liked.containsKey(id)
                        ? MusicFavoriteStatusView.liked(liked.get(id))
                        : MusicFavoriteStatusView.notLiked(source.getValue(), id))
                .collect(Collectors.toList());
    }

    private void trimHistory(Long userId) {
        long count = historyRepository.countByUserId(userId);
        if (count > MAX_HISTORY_SIZE) {
            int excess = (int) (count - MAX_HISTORY_SIZE);
            java.util.List<Long> ids = historyRepository.findOldestIds(
                    userId,
                    org.springframework.data.domain.PageRequest.of(0, excess)
            );
            if (!ids.isEmpty()) {
                historyRepository.deleteByIdIn(ids);
            }
        }
    }

    private void applyTrackFields(MusicPlayHistory entity,
                                  String name,
                                  String artist,
                                  String album,
                                  String coverUrl,
                                  Integer durationSec) {
        entity.setName(limit(firstNonBlank(name, entity.getSongId()), 300));
        entity.setArtist(limit(trimmedOrNull(artist), 300));
        entity.setAlbum(limit(trimmedOrNull(album), 300));
        entity.setCoverUrl(limit(trimmedOrNull(coverUrl), 1000));
        entity.setDurationSec(normalizeDuration(durationSec));
    }

    private void applyTrackFields(MusicFavorite entity,
                                  String name,
                                  String artist,
                                  String album,
                                  String coverUrl,
                                  Integer durationSec) {
        entity.setName(limit(requireText(name, "name is required"), 300));
        entity.setArtist(limit(trimmedOrNull(artist), 300));
        entity.setAlbum(limit(trimmedOrNull(album), 300));
        entity.setCoverUrl(limit(trimmedOrNull(coverUrl), 1000));
        entity.setDurationSec(normalizeDuration(durationSec));
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private Integer normalizeDuration(Integer durationSec) {
        if (durationSec == null || durationSec <= 0) {
            return null;
        }
        return durationSec;
    }

    private String requireText(String value, String message) {
        String trimmed = trimmedOrNull(value);
        if (trimmed == null) {
            throw new BusinessException(400, message);
        }
        return trimmed;
    }

    private String firstNonBlank(String first, String fallback) {
        String value = trimmedOrNull(first);
        return value != null ? value : fallback;
    }

    private String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String limit(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }
}
