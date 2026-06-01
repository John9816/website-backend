package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.LinkRequest;
import com.example.website.dto.LinkView;
import com.example.website.entity.NavLink;
import com.example.website.repository.CategoryRepository;
import com.example.website.repository.NavLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LinkService {

    private final NavLinkRepository linkRepository;
    private final CategoryRepository categoryRepository;

    public List<LinkView> listAll(Long userId) {
        return linkRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .map(LinkView::from)
                .collect(Collectors.toList());
    }

    public List<LinkView> listByCategory(Long userId, Long categoryId) {
        if (!categoryRepository.existsByIdAndUserId(categoryId, userId)) {
            throw new BusinessException(404, "Category not found");
        }
        return linkRepository.findByCategoryIdAndUserIdOrderBySortOrderAscIdAsc(categoryId, userId).stream()
                .map(LinkView::from)
                .collect(Collectors.toList());
    }

    public LinkView get(Long userId, Long id) {
        return linkRepository.findByIdAndUserId(id, userId)
                .map(LinkView::from)
                .orElseThrow(() -> new BusinessException(404, "Link not found"));
    }

    @Transactional
    public LinkView create(Long userId, LinkRequest req) {
        ensureCategory(userId, req.getCategoryId());
        NavLink n = new NavLink();
        apply(userId, n, req);
        return LinkView.from(linkRepository.save(n));
    }

    @Transactional
    public LinkView update(Long userId, Long id, LinkRequest req) {
        NavLink n = linkRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Link not found"));
        ensureCategory(userId, req.getCategoryId());
        apply(userId, n, req);
        return LinkView.from(linkRepository.save(n));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        NavLink link = linkRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Link not found"));
        linkRepository.delete(link);
    }

    private void ensureCategory(Long userId, Long categoryId) {
        if (!categoryRepository.existsByIdAndUserId(categoryId, userId)) {
            throw new BusinessException(404, "Category not found: " + categoryId);
        }
    }

    private void apply(Long userId, NavLink n, LinkRequest req) {
        n.setUserId(userId);
        n.setCategoryId(req.getCategoryId());
        n.setName(req.getName());
        n.setUrl(req.getUrl());
        n.setDescription(req.getDescription());
        n.setIcon(req.getIcon());
        n.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
    }
}
