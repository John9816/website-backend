package com.example.website.service.kb;

import com.example.website.common.BusinessException;
import com.example.website.dto.kb.KbTagRequest;
import com.example.website.dto.kb.KbTagView;
import com.example.website.entity.KbTag;
import com.example.website.repository.KbTagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KbTagService {

    private final KbTagRepository tagRepository;

    public List<KbTagView> list(Long userId) {
        return tagRepository.findByUserIdOrderByNameAsc(userId).stream()
                .map(KbTagView::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public KbTagView create(Long userId, KbTagRequest req) {
        Optional<KbTag> existing = tagRepository.findByUserIdAndName(userId, req.getName());
        if (existing.isPresent()) {
            throw new BusinessException(409, "Tag already exists: " + req.getName());
        }
        KbTag t = new KbTag();
        t.setUserId(userId);
        t.setName(req.getName());
        t.setColor(req.getColor());
        return KbTagView.from(tagRepository.save(t));
    }

    @Transactional
    public KbTagView update(Long userId, Long id, KbTagRequest req) {
        KbTag t = tagRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Tag not found"));
        if (!t.getName().equals(req.getName())) {
            tagRepository.findByUserIdAndName(userId, req.getName()).ifPresent(other -> {
                throw new BusinessException(409, "Tag already exists: " + req.getName());
            });
        }
        t.setName(req.getName());
        t.setColor(req.getColor());
        return KbTagView.from(tagRepository.save(t));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        KbTag t = tagRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Tag not found"));
        tagRepository.delete(t);
    }

    public List<KbTag> requireAllOwned(Long userId, Collection<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<KbTag> tags = tagRepository.findByIdInAndUserId(tagIds, userId);
        if (tags.size() != tagIds.size()) {
            throw new BusinessException(404, "One or more tags not found");
        }
        return tags;
    }
}
