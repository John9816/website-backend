package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.CategoryRequest;
import com.example.website.dto.CategoryView;
import com.example.website.dto.LinkView;
import com.example.website.entity.Category;
import com.example.website.entity.NavLink;
import com.example.website.repository.CategoryRepository;
import com.example.website.repository.NavLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final NavLinkRepository linkRepository;

    public List<CategoryView> listAll(Long userId) {
        return categoryRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .map(CategoryView::from)
                .collect(Collectors.toList());
    }

    public List<CategoryView> listAllWithLinks(Long userId) {
        List<Category> categories = categoryRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
        Map<Long, List<LinkView>> linksByCategory = linkRepository.findByUserIdOrderBySortOrderAscIdAsc(userId)
                .stream()
                .map(LinkView::from)
                .collect(Collectors.groupingBy(LinkView::getCategoryId));
        return categories.stream().map(c -> {
            CategoryView v = CategoryView.from(c);
            v.setLinks(linksByCategory.getOrDefault(c.getId(), java.util.Collections.emptyList()));
            return v;
        }).collect(Collectors.toList());
    }

    public CategoryView get(Long userId, Long id) {
        return categoryRepository.findByIdAndUserId(id, userId)
                .map(CategoryView::from)
                .orElseThrow(() -> new BusinessException(404, "Category not found"));
    }

    @Transactional
    public CategoryView create(Long userId, CategoryRequest req) {
        Category c = new Category();
        c.setName(req.getName());
        c.setIcon(req.getIcon());
        c.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        c.setUserId(userId);
        return CategoryView.from(categoryRepository.save(c));
    }

    @Transactional
    public CategoryView update(Long userId, Long id, CategoryRequest req) {
        Category c = categoryRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Category not found"));
        c.setName(req.getName());
        c.setIcon(req.getIcon());
        if (req.getSortOrder() != null) {
            c.setSortOrder(req.getSortOrder());
        }
        return CategoryView.from(categoryRepository.save(c));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Category category = categoryRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Category not found"));
        linkRepository.deleteByCategoryIdAndUserId(id, userId);
        categoryRepository.delete(category);
    }
}
