package com.example.website.service.music;

import com.example.website.common.BusinessException;
import com.example.website.dto.music.MusicPublicShareView;
import com.example.website.dto.music.MusicQuality;
import com.example.website.dto.music.MusicShareRequest;
import com.example.website.dto.music.MusicShareView;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.PlayInfo;
import com.example.website.entity.MusicShare;
import com.example.website.repository.MusicShareRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MusicShareService {

    private final MusicShareRepository shareRepository;
    private final MusicService musicService;
    private final TransactionTemplate transactionTemplate;

    public MusicShareView get(Long userId, String rawSource, String rawSongId) {
        MusicSource source = MusicSource.of(rawSource);
        String songId = requireText(rawSongId, "songId is required");
        return shareRepository.findByUserIdAndSourceAndSongId(userId, source.getValue(), songId)
                .map(MusicShareView::from)
                .orElse(null);
    }

    @Transactional
    public MusicShareView save(Long userId, MusicShareRequest req) {
        MusicSource source = MusicSource.of(req.getSource());
        String songId = requireText(req.getSongId(), "songId is required");
        MusicShare entity = shareRepository.findByUserIdAndSourceAndSongId(userId, source.getValue(), songId)
                .orElseGet(MusicShare::new);
        if (entity.getId() == null) {
            entity.setUserId(userId);
            entity.setSource(source.getValue());
            entity.setSongId(songId);
            entity.setToken(generateToken());
            entity.setViewCount(0);
        } else if (Boolean.TRUE.equals(req.getRotateToken())) {
            entity.setToken(generateToken());
        }
        applyTrackFields(entity, req);
        entity.setRequestedQuality(MusicQuality.of(trimmedOrNull(req.getRequestedQuality())).getValue());
        entity.setExpiresAt(req.getExpiresAt());
        return MusicShareView.from(shareRepository.save(entity));
    }

    @Transactional
    public void delete(Long userId, String rawSource, String rawSongId) {
        MusicSource source = MusicSource.of(rawSource);
        String songId = requireText(rawSongId, "songId is required");
        MusicShare entity = shareRepository.findByUserIdAndSourceAndSongId(userId, source.getValue(), songId)
                .orElseThrow(() -> new BusinessException(404, "Music share not found"));
        shareRepository.delete(entity);
    }

    public MusicPublicShareView view(String token) {
        // Step 1: read share and bump view count in a short transaction; release the DB
        // connection before doing any upstream HTTP work in musicService.play.
        MusicShare share = transactionTemplate.execute(status -> {
            MusicShare s = shareRepository.findByToken(requireText(token, "token is required"))
                    .orElseThrow(() -> new BusinessException(404, "Share not found"));
            if (s.getExpiresAt() != null && s.getExpiresAt().isBefore(LocalDateTime.now())) {
                throw new BusinessException(410, "Share link expired");
            }
            shareRepository.incrementViewCount(s.getId());
            s.setViewCount((s.getViewCount() == null ? 0 : s.getViewCount()) + 1);
            return s;
        });

        // Step 2: resolve play info outside any DB transaction.
        PlayInfo playInfo = null;
        String playError = null;
        try {
            playInfo = musicService.play(share.getSource(), share.getSongId(), share.getRequestedQuality());
        } catch (Exception ex) {
            playError = ex.getMessage();
            log.warn("Music share play resolution failed for token {}: {}", share.getToken(), ex.getMessage());
        }
        return MusicPublicShareView.from(share, playInfo, playError);
    }

    private void applyTrackFields(MusicShare entity, MusicShareRequest req) {
        entity.setName(requireText(req.getName(), "name is required"));
        entity.setArtist(trimmedOrNull(req.getArtist()));
        entity.setAlbum(trimmedOrNull(req.getAlbum()));
        entity.setCoverUrl(trimmedOrNull(req.getCoverUrl()));
        entity.setDurationSec(normalizeDuration(req.getDurationSec()));
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

    private String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String generateToken() {
        for (int i = 0; i < 5; i++) {
            String token = UUID.randomUUID().toString().replace("-", "");
            if (!shareRepository.existsByToken(token)) {
                return token;
            }
        }
        throw new BusinessException(500, "Failed to allocate share token");
    }
}
