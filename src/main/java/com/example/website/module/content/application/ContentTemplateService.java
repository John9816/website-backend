package com.example.website.module.content.application;

import com.example.website.module.content.domain.ContentTemplate;
import com.example.website.module.content.infrastructure.persistence.ContentTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContentTemplateService {

    private final ContentTemplateRepository templateRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建模板
     */
    @Transactional
    public Map<String, Object> createTemplate(Long userId, Map<String, Object> request) {
        ContentTemplate template = new ContentTemplate();
        template.setUserId(userId);
        template.setName(getString(request, "name"));
        template.setDescription(getStringOrNull(request, "description"));
        template.setCategory(getStringOrNull(request, "category"));
        template.setTemplateType(getStringOrDefault(request, "templateType", "CUSTOM"));
        template.setContentTemplate(getString(request, "contentTemplate"));
        template.setPromptTemplate(getStringOrNull(request, "promptTemplate"));
        template.setDefaultAngle(getStringOrNull(request, "defaultAngle"));
        template.setDefaultAudience(getStringOrNull(request, "defaultAudience"));
        template.setDefaultTone(getStringOrNull(request, "defaultTone"));
        template.setCoverStyle(getStringOrNull(request, "coverStyle"));
        template.setLayoutTheme(getStringOrDefault(request, "layoutTheme", "clean"));
        template.setImageMode(getStringOrDefault(request, "imageMode", "generate"));

        if (request.containsKey("tags")) {
            try {
                template.setTagsJson(objectMapper.writeValueAsString(request.get("tags")));
            } catch (Exception e) {
                log.error("序列化tags失败", e);
            }
        }

        template.setSortOrder(getIntOrDefault(request, "sortOrder", 0));
        template.setIsActive(getBooleanOrDefault(request, "isActive", true));

        template = templateRepository.save(template);
        log.info("用户 {} 创建内容模板: {}", userId, template.getId());

        return buildTemplateResponse(template);
    }

    /**
     * 更新模板
     */
    @Transactional
    public Map<String, Object> updateTemplate(Long userId, Long templateId, Map<String, Object> request) {
        ContentTemplate template = templateRepository.findByIdAndUserId(templateId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在"));

        if (request.containsKey("name")) {
            template.setName(getString(request, "name"));
        }
        if (request.containsKey("description")) {
            template.setDescription(getStringOrNull(request, "description"));
        }
        if (request.containsKey("category")) {
            template.setCategory(getStringOrNull(request, "category"));
        }
        if (request.containsKey("contentTemplate")) {
            template.setContentTemplate(getString(request, "contentTemplate"));
        }
        if (request.containsKey("promptTemplate")) {
            template.setPromptTemplate(getStringOrNull(request, "promptTemplate"));
        }
        if (request.containsKey("defaultAngle")) {
            template.setDefaultAngle(getStringOrNull(request, "defaultAngle"));
        }
        if (request.containsKey("defaultAudience")) {
            template.setDefaultAudience(getStringOrNull(request, "defaultAudience"));
        }
        if (request.containsKey("defaultTone")) {
            template.setDefaultTone(getStringOrNull(request, "defaultTone"));
        }
        if (request.containsKey("coverStyle")) {
            template.setCoverStyle(getStringOrNull(request, "coverStyle"));
        }
        if (request.containsKey("layoutTheme")) {
            template.setLayoutTheme(getString(request, "layoutTheme"));
        }
        if (request.containsKey("imageMode")) {
            template.setImageMode(getString(request, "imageMode"));
        }
        if (request.containsKey("tags")) {
            try {
                template.setTagsJson(objectMapper.writeValueAsString(request.get("tags")));
            } catch (Exception e) {
                log.error("序列化tags失败", e);
            }
        }
        if (request.containsKey("sortOrder")) {
            template.setSortOrder(getInt(request, "sortOrder"));
        }
        if (request.containsKey("isActive")) {
            template.setIsActive(getBoolean(request, "isActive"));
        }

        template = templateRepository.save(template);
        log.info("用户 {} 更新内容模板: {}", userId, templateId);

        return buildTemplateResponse(template);
    }

    /**
     * 删除模板
     */
    @Transactional
    public void deleteTemplate(Long userId, Long templateId) {
        ContentTemplate template = templateRepository.findByIdAndUserId(templateId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在"));

        templateRepository.delete(template);
        log.info("用户 {} 删除内容模板: {}", userId, templateId);
    }

    /**
     * 获取模板详情
     */
    public Map<String, Object> getTemplate(Long userId, Long templateId) {
        ContentTemplate template = templateRepository.findByIdAndUserId(templateId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在"));

        return buildTemplateResponse(template);
    }

    /**
     * 获取用户的模板列表
     */
    public Page<Map<String, Object>> getTemplates(Long userId, Boolean onlyActive, Pageable pageable) {
        Page<ContentTemplate> templates;
        if (onlyActive != null && onlyActive) {
            templates = templateRepository.findByUserIdAndIsActiveOrderBySortOrderAscIdDesc(userId, true, pageable);
        } else {
            templates = templateRepository.findByUserIdOrderBySortOrderAscIdDesc(userId, pageable);
        }

        return templates.map(this::buildTemplateListItem);
    }

    /**
     * 按分类获取模板
     */
    public List<Map<String, Object>> getTemplatesByCategory(Long userId, String category) {
        List<ContentTemplate> templates = templateRepository
                .findByUserIdAndCategoryAndIsActiveOrderBySortOrderAsc(userId, category, true);

        return templates.stream()
                .map(this::buildTemplateListItem)
                .toList();
    }

    /**
     * 搜索模板
     */
    public Page<Map<String, Object>> searchTemplates(Long userId, String keyword, Pageable pageable) {
        return templateRepository.searchByKeyword(userId, keyword, pageable)
                .map(this::buildTemplateListItem);
    }

    /**
     * 获取所有分类
     */
    public List<String> getCategories(Long userId) {
        return templateRepository.findDistinctCategoriesByUserId(userId);
    }

    /**
     * 增加使用次数
     */
    @Transactional
    public void incrementUsageCount(Long templateId) {
        templateRepository.findById(templateId).ifPresent(template -> {
            template.setUsageCount(template.getUsageCount() + 1);
            templateRepository.save(template);
        });
    }

    /**
     * 复制模板
     */
    @Transactional
    public Map<String, Object> duplicateTemplate(Long userId, Long templateId) {
        ContentTemplate source = templateRepository.findByIdAndUserId(templateId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在"));

        ContentTemplate duplicate = new ContentTemplate();
        duplicate.setUserId(userId);
        duplicate.setName(source.getName() + " (副本)");
        duplicate.setDescription(source.getDescription());
        duplicate.setCategory(source.getCategory());
        duplicate.setTemplateType("CUSTOM");
        duplicate.setContentTemplate(source.getContentTemplate());
        duplicate.setPromptTemplate(source.getPromptTemplate());
        duplicate.setDefaultAngle(source.getDefaultAngle());
        duplicate.setDefaultAudience(source.getDefaultAudience());
        duplicate.setDefaultTone(source.getDefaultTone());
        duplicate.setCoverStyle(source.getCoverStyle());
        duplicate.setLayoutTheme(source.getLayoutTheme());
        duplicate.setImageMode(source.getImageMode());
        duplicate.setTagsJson(source.getTagsJson());
        duplicate.setSortOrder(source.getSortOrder());
        duplicate.setIsActive(true);

        duplicate = templateRepository.save(duplicate);
        log.info("用户 {} 复制内容模板 {} -> {}", userId, templateId, duplicate.getId());

        return buildTemplateResponse(duplicate);
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics(Long userId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalTemplates", templateRepository.countByUserIdAndIsActive(userId, null));
        stats.put("activeTemplates", templateRepository.countByUserIdAndIsActive(userId, true));
        stats.put("categories", templateRepository.findDistinctCategoriesByUserId(userId).size());

        return stats;
    }

    // ==================== 私有辅助方法 ====================

    private Map<String, Object> buildTemplateResponse(ContentTemplate template) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", template.getId());
        result.put("userId", template.getUserId());
        result.put("name", template.getName());
        result.put("description", template.getDescription());
        result.put("category", template.getCategory());
        result.put("templateType", template.getTemplateType());
        result.put("contentTemplate", template.getContentTemplate());
        result.put("promptTemplate", template.getPromptTemplate());
        result.put("defaultAngle", template.getDefaultAngle());
        result.put("defaultAudience", template.getDefaultAudience());
        result.put("defaultTone", template.getDefaultTone());
        result.put("coverStyle", template.getCoverStyle());
        result.put("layoutTheme", template.getLayoutTheme());
        result.put("imageMode", template.getImageMode());
        result.put("tags", parseJson(template.getTagsJson()));
        result.put("sortOrder", template.getSortOrder());
        result.put("isActive", template.getIsActive());
        result.put("usageCount", template.getUsageCount());
        result.put("createdAt", template.getCreatedAt());
        result.put("updatedAt", template.getUpdatedAt());
        return result;
    }

    private Map<String, Object> buildTemplateListItem(ContentTemplate template) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", template.getId());
        result.put("name", template.getName());
        result.put("description", template.getDescription());
        result.put("category", template.getCategory());
        result.put("templateType", template.getTemplateType());
        result.put("layoutTheme", template.getLayoutTheme());
        result.put("tags", parseJson(template.getTagsJson()));
        result.put("isActive", template.getIsActive());
        result.put("usageCount", template.getUsageCount());
        result.put("updatedAt", template.getUpdatedAt());
        return result;
    }

    private Object parseJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 不能为空");
        }
        return value.toString();
    }

    private String getStringOrNull(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private String getStringOrDefault(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private Integer getInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 不能为空");
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private Integer getIntOrDefault(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private Boolean getBoolean(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 不能为空");
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private Boolean getBooleanOrDefault(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }
}
