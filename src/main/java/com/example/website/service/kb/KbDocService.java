package com.example.website.service.kb;

import com.example.website.common.BusinessException;
import com.example.website.dto.PageView;
import com.example.website.dto.kb.KbDocCreateRequest;
import com.example.website.dto.kb.KbDocMoveRequest;
import com.example.website.dto.kb.KbDocShareView;
import com.example.website.dto.kb.KbDocSummaryView;
import com.example.website.dto.kb.KbDocTreeNodeView;
import com.example.website.dto.kb.KbDocUpdateRequest;
import com.example.website.dto.kb.KbDocVersionDetailView;
import com.example.website.dto.kb.KbDocVersionView;
import com.example.website.dto.kb.KbDocView;
import com.example.website.dto.kb.KbTagView;
import com.example.website.entity.KbDoc;
import com.example.website.entity.KbDocShare;
import com.example.website.entity.KbDocTag;
import com.example.website.entity.KbDocVersion;
import com.example.website.entity.KbSpace;
import com.example.website.entity.KbTag;
import com.example.website.repository.KbDocRepository;
import com.example.website.repository.KbDocShareRepository;
import com.example.website.repository.KbDocTagRepository;
import com.example.website.repository.KbDocVersionRepository;
import com.example.website.repository.KbSpaceRepository;
import com.example.website.repository.KbTagRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KbDocService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int VERSION_PAGE_SIZE_DEFAULT = 20;

    private final KbDocRepository docRepository;
    private final KbDocVersionRepository versionRepository;
    private final KbDocTagRepository docTagRepository;
    private final KbDocShareRepository shareRepository;
    private final KbSpaceRepository spaceRepository;
    private final KbTagRepository tagRepository;
    private final KbSpaceService spaceService;
    private final KbTagService tagService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KbDocView get(Long userId, Long id) {
        KbDoc d = requireOwned(userId, id);
        return assembleView(d);
    }

    public KbDoc requireOwned(Long userId, Long id) {
        return docRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Document not found"));
    }

    public PageView<KbDocSummaryView> search(Long userId, Long spaceId, Long parentId, String keyword,
                                              Long tagId, int page, int size) {
        if (spaceId != null) {
            spaceService.requireOwned(userId, spaceId);
        }
        boolean hasParent = parentId != null;
        boolean hasIdFilter = false;
        Collection<Long> ids = Collections.emptyList();
        if (tagId != null) {
            if (!tagRepository.existsByIdAndUserId(tagId, userId)) {
                throw new BusinessException(404, "Tag not found");
            }
            ids = docTagRepository.findDocIdsByTagId(tagId);
            if (ids.isEmpty()) {
                return new PageView<>(Collections.emptyList(), 0L, page, normalizeSize(size));
            }
            hasIdFilter = true;
        }
        String like = keyword == null || keyword.trim().isEmpty()
                ? null
                : "%" + keyword.trim().toLowerCase() + "%";
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size));
        Page<KbDocRepository.KbDocSummary> result = docRepository.search(
                userId, spaceId, hasParent, parentId, hasIdFilter, ids, like, pageable);
        return PageView.from(result, KbDocSummaryView::from);
    }

    public List<KbDocTreeNodeView> tree(Long userId, Long spaceId) {
        spaceService.requireOwned(userId, spaceId);
        List<KbDocRepository.KbDocSummary> rows = docRepository.findSummariesBySpaceAndUser(spaceId, userId);
        Map<Long, KbDocTreeNodeView> nodes = new HashMap<>();
        for (KbDocRepository.KbDocSummary row : rows) {
            KbDocTreeNodeView node = new KbDocTreeNodeView();
            node.setId(row.getId());
            node.setParentId(row.getParentId());
            node.setTitle(row.getTitle());
            node.setStatus(row.getStatus());
            node.setSortOrder(row.getSortOrder());
            nodes.put(row.getId(), node);
        }
        List<KbDocTreeNodeView> roots = new ArrayList<>();
        for (KbDocTreeNodeView n : nodes.values()) {
            if (n.getParentId() == null) {
                roots.add(n);
            } else {
                KbDocTreeNodeView parent = nodes.get(n.getParentId());
                if (parent != null) {
                    parent.getChildren().add(n);
                } else {
                    roots.add(n);
                }
            }
        }
        return roots;
    }

    @Transactional
    public KbDocView create(Long userId, KbDocCreateRequest req) {
        spaceService.requireOwned(userId, req.getSpaceId());
        if (req.getParentId() != null) {
            KbDoc parent = requireOwned(userId, req.getParentId());
            if (!parent.getSpaceId().equals(req.getSpaceId())) {
                throw new BusinessException(400, "Parent doc belongs to a different space");
            }
        }
        validateJson(req.getContentJson());

        KbDoc d = new KbDoc();
        d.setUserId(userId);
        d.setSpaceId(req.getSpaceId());
        d.setParentId(req.getParentId());
        d.setTitle(req.getTitle());
        d.setSummary(req.getSummary());
        d.setContentJson(req.getContentJson());
        d.setContentHtml(req.getContentHtml());
        d.setStatus(normalizeStatus(req.getStatus()));
        d.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        d.setVersionNo(1);
        d = docRepository.save(d);

        snapshot(d, userId, "initial");
        spaceRepository.adjustDocCount(d.getSpaceId(), 1);
        return assembleView(d);
    }

    @Transactional
    public KbDocView update(Long userId, Long id, KbDocUpdateRequest req) {
        KbDoc d = requireOwned(userId, id);
        validateJson(req.getContentJson());

        d.setTitle(req.getTitle());
        d.setSummary(req.getSummary());
        d.setContentJson(req.getContentJson());
        d.setContentHtml(req.getContentHtml());
        if (req.getStatus() != null && !req.getStatus().isEmpty()) {
            d.setStatus(normalizeStatus(req.getStatus()));
        }
        if (req.getSortOrder() != null) {
            d.setSortOrder(req.getSortOrder());
        }
        d.setVersionNo(d.getVersionNo() + 1);
        d = docRepository.save(d);

        snapshot(d, userId, req.getChangeNote());
        return assembleView(d);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        KbDoc d = requireOwned(userId, id);
        docRepository.repointChildren(d.getId(), d.getParentId(), userId, LocalDateTime.now());
        docRepository.delete(d);
        spaceRepository.adjustDocCount(d.getSpaceId(), -1);
    }

    @Transactional
    public KbDocView move(Long userId, Long id, KbDocMoveRequest req) {
        KbDoc d = requireOwned(userId, id);
        spaceService.requireOwned(userId, req.getSpaceId());

        if (req.getParentId() != null) {
            if (req.getParentId().equals(id)) {
                throw new BusinessException(400, "Cannot move a document into itself");
            }
            KbDoc target = requireOwned(userId, req.getParentId());
            if (!target.getSpaceId().equals(req.getSpaceId())) {
                throw new BusinessException(400, "Target parent belongs to a different space");
            }
            assertNotDescendant(userId, target, id);
        }

        Long oldSpaceId = d.getSpaceId();
        d.setSpaceId(req.getSpaceId());
        d.setParentId(req.getParentId());
        if (req.getSortOrder() != null) {
            d.setSortOrder(req.getSortOrder());
        }
        d = docRepository.save(d);

        if (!oldSpaceId.equals(d.getSpaceId())) {
            spaceRepository.adjustDocCount(oldSpaceId, -1);
            spaceRepository.adjustDocCount(d.getSpaceId(), 1);
        }
        return assembleView(d);
    }

    @Transactional
    public KbDocView replaceTags(Long userId, Long id, List<Long> tagIds) {
        KbDoc d = requireOwned(userId, id);
        Set<Long> targetIds = tagIds == null ? Collections.emptySet() : new HashSet<>(tagIds);
        tagService.requireAllOwned(userId, targetIds);
        docTagRepository.deleteByDocId(d.getId());
        if (!targetIds.isEmpty()) {
            List<KbDocTag> toInsert = targetIds.stream()
                    .map(tagId -> new KbDocTag(d.getId(), tagId))
                    .collect(Collectors.toList());
            docTagRepository.saveAll(toInsert);
        }
        return assembleView(d);
    }

    public PageView<KbDocVersionView> listVersions(Long userId, Long docId, int page, int size) {
        requireOwned(userId, docId);
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizePageSize(size, VERSION_PAGE_SIZE_DEFAULT));
        Page<KbDocVersionRepository.KbDocVersionSummary> result =
                versionRepository.findSummariesByDocId(docId, pageable);
        return PageView.from(result, KbDocVersionView::from);
    }

    public KbDocVersionDetailView getVersion(Long userId, Long docId, Long versionId) {
        requireOwned(userId, docId);
        KbDocVersion v = versionRepository.findByIdAndDocId(versionId, docId)
                .orElseThrow(() -> new BusinessException(404, "Version not found"));
        return KbDocVersionDetailView.from(v);
    }

    @Transactional
    public KbDocView restoreVersion(Long userId, Long docId, Long versionId) {
        KbDoc d = requireOwned(userId, docId);
        KbDocVersion v = versionRepository.findByIdAndDocId(versionId, docId)
                .orElseThrow(() -> new BusinessException(404, "Version not found"));

        d.setTitle(v.getTitle());
        d.setSummary(v.getSummary());
        d.setContentJson(v.getContentJson());
        d.setContentHtml(v.getContentHtml());
        d.setVersionNo(d.getVersionNo() + 1);
        d = docRepository.save(d);

        snapshot(d, userId, "restored from v" + v.getVersionNo());
        return assembleView(d);
    }

    KbDocView assembleView(KbDoc d) {
        List<KbDocTag> mappings = docTagRepository.findByDocId(d.getId());
        List<KbTagView> tags;
        if (mappings.isEmpty()) {
            tags = Collections.emptyList();
        } else {
            List<Long> tagIds = mappings.stream().map(KbDocTag::getTagId).collect(Collectors.toList());
            List<KbTag> tagEntities = tagRepository.findByIdInAndUserId(tagIds, d.getUserId());
            tags = tagEntities.stream().map(KbTagView::from).collect(Collectors.toList());
        }
        KbDocShareView share = shareRepository.findByDocId(d.getId())
                .map(KbDocShareView::from)
                .orElse(null);
        return KbDocView.from(d, tags, share);
    }

    private void snapshot(KbDoc d, Long editorUserId, String changeNote) {
        KbDocVersion v = new KbDocVersion();
        v.setDocId(d.getId());
        v.setVersionNo(d.getVersionNo());
        v.setTitle(d.getTitle());
        v.setSummary(d.getSummary());
        v.setContentJson(d.getContentJson());
        v.setContentHtml(d.getContentHtml());
        v.setEditorUserId(editorUserId);
        v.setChangeNote(changeNote);
        versionRepository.save(v);
    }

    private void assertNotDescendant(Long userId, KbDoc candidateParent, Long ancestorId) {
        Long cursor = candidateParent.getParentId();
        Set<Long> visited = new HashSet<>();
        while (cursor != null) {
            if (cursor.equals(ancestorId)) {
                throw new BusinessException(400, "Cannot move a document into one of its descendants");
            }
            if (!visited.add(cursor)) {
                throw new BusinessException(500, "Cycle detected in document hierarchy");
            }
            KbDoc up = docRepository.findByIdAndUserId(cursor, userId).orElse(null);
            cursor = up == null ? null : up.getParentId();
        }
    }

    private void validateJson(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            objectMapper.readTree(json);
        } catch (Exception e) {
            throw new BusinessException(400, "contentJson is not valid JSON");
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isEmpty()) return KbDoc.STATUS_DRAFT;
        if (KbDoc.STATUS_DRAFT.equalsIgnoreCase(status)) return KbDoc.STATUS_DRAFT;
        if (KbDoc.STATUS_PUBLISHED.equalsIgnoreCase(status)) return KbDoc.STATUS_PUBLISHED;
        throw new BusinessException(400, "status must be draft or published");
    }

    private int normalizeSize(int size) {
        return normalizePageSize(size, DEFAULT_PAGE_SIZE);
    }

    private int normalizePageSize(int size, int fallback) {
        if (size <= 0) return fallback;
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
