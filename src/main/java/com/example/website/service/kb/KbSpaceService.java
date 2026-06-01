package com.example.website.service.kb;

import com.example.website.common.BusinessException;
import com.example.website.dto.kb.KbSpaceRequest;
import com.example.website.dto.kb.KbSpaceView;
import com.example.website.entity.KbSpace;
import com.example.website.repository.KbSpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KbSpaceService {

    private final KbSpaceRepository spaceRepository;

    public List<KbSpaceView> list(Long userId) {
        return spaceRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .map(KbSpaceView::from)
                .collect(Collectors.toList());
    }

    public KbSpaceView get(Long userId, Long id) {
        return KbSpaceView.from(requireOwned(userId, id));
    }

    public KbSpace requireOwned(Long userId, Long id) {
        return spaceRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Space not found"));
    }

    @Transactional
    public KbSpaceView create(Long userId, KbSpaceRequest req) {
        if (spaceRepository.existsByUserId(userId)) {
            throw new BusinessException(400, "One account can only create one space");
        }
        KbSpace s = new KbSpace();
        s.setUserId(userId);
        apply(s, req);
        return KbSpaceView.from(spaceRepository.save(s));
    }

    @Transactional
    public KbSpaceView update(Long userId, Long id, KbSpaceRequest req) {
        KbSpace s = requireOwned(userId, id);
        apply(s, req);
        return KbSpaceView.from(spaceRepository.save(s));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        KbSpace s = requireOwned(userId, id);
        spaceRepository.delete(s);
    }

    private void apply(KbSpace s, KbSpaceRequest req) {
        s.setName(req.getName());
        s.setDescription(req.getDescription());
        s.setIcon(req.getIcon());
        s.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
    }
}
