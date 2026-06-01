package com.example.website.service.kb;

import com.example.website.common.BusinessException;
import com.example.website.dto.kb.KbDocShareRequest;
import com.example.website.dto.kb.KbDocShareView;
import com.example.website.dto.kb.KbPublicDocItemView;
import com.example.website.dto.kb.KbPublicDocView;
import com.example.website.entity.KbDoc;
import com.example.website.entity.KbDocShare;
import com.example.website.repository.KbDocRepository;
import com.example.website.repository.KbDocShareRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KbDocShareService {

    private final KbDocShareRepository shareRepository;
    private final KbDocRepository docRepository;
    private final KbDocService docService;

    public KbDocShareView get(Long userId, Long docId) {
        docService.requireOwned(userId, docId);
        return shareRepository.findByDocId(docId)
                .map(KbDocShareView::from)
                .orElse(null);
    }

    @Transactional
    public KbDocShareView enable(Long userId, Long docId, KbDocShareRequest req) {
        docService.requireOwned(userId, docId);
        KbDocShare s = shareRepository.findByDocId(docId).orElse(null);
        boolean rotate = req != null && Boolean.TRUE.equals(req.getRotateToken());
        if (s == null) {
            s = new KbDocShare();
            s.setDocId(docId);
            s.setUserId(userId);
            s.setToken(generateToken());
            s.setEnabled(Boolean.TRUE);
            s.setViewCount(0);
        } else if (rotate) {
            s.setToken(generateToken());
        }
        if (req != null) {
            if (req.getEnabled() != null) {
                s.setEnabled(req.getEnabled());
            } else {
                s.setEnabled(Boolean.TRUE);
            }
            s.setExpiresAt(req.getExpiresAt());
        } else {
            s.setEnabled(Boolean.TRUE);
        }
        return KbDocShareView.from(shareRepository.save(s));
    }

    @Transactional
    public void disable(Long userId, Long docId) {
        docService.requireOwned(userId, docId);
        shareRepository.findByDocId(docId).ifPresent(shareRepository::delete);
    }

    @Transactional
    public KbPublicDocView view(String token) {
        LocalDateTime now = LocalDateTime.now();
        KbDocShare s = shareRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(404, "Share not found"));
        if (Boolean.FALSE.equals(s.getEnabled())) {
            throw new BusinessException(404, "Share not found");
        }
        if (s.getExpiresAt() != null && s.getExpiresAt().isBefore(now)) {
            throw new BusinessException(410, "Share link expired");
        }
        KbDoc d = docRepository.findById(s.getDocId())
                .orElseThrow(() -> new BusinessException(404, "Document not found"));
        shareRepository.incrementViewCount(s.getId());
        List<KbPublicDocItemView> documents = shareRepository
                .findActivePublicDocItemsByUserId(s.getUserId(), d.getId(), now)
                .stream()
                .map(KbPublicDocItemView::from)
                .collect(Collectors.toList());
        return KbPublicDocView.from(d, s.getToken(), documents);
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
