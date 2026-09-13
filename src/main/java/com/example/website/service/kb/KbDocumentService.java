package com.example.website.service.kb;

import com.example.website.entity.KbDocument;
import com.example.website.repository.KbDocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class KbDocumentService {

    private final KbDocumentRepository documentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder().build();

    /**
     * 创建文档
     */
    @Transactional
    @CacheEvict(value = {"kb-documents", "kb-space-stats"}, allEntries = true)
    public Map<String, Object> createDocument(Long userId, Map<String, Object> request) {
        KbDocument document = new KbDocument();
        document.setUserId(userId);
        document.setSpaceId(getLong(request, "spaceId"));
        document.setTitle(getString(request, "title"));

        String content = getStringOrNull(request, "content");
        document.setContent(content);

        // 自动转换 Markdown 为 HTML
        if (content != null && !content.isEmpty()) {
            document.setContentHtml(markdownToHtml(content));
            document.setWordCount(countWords(content));
            document.setReadingTimeMinutes(calculateReadingTime(content));
        }

        document.setSlug(generateSlug(document.getTitle()));
        document.setParentId(getLongOrNull(request, "parentId"));
        document.setSortOrder(getIntOrDefault(request, "sortOrder", 0));
        document.setStatus(getStringOrDefault(request, "status", "DRAFT"));
        document.setVisibility(getStringOrDefault(request, "visibility", "PRIVATE"));
        document.setCoverImageUrl(getStringOrNull(request, "coverImageUrl"));

        if (request.containsKey("tags")) {
            try {
                document.setTagsJson(objectMapper.writeValueAsString(request.get("tags")));
            } catch (Exception e) {
                log.error("序列化tags失败", e);
            }
        }

        document.setLastEditedBy(userId);

        // 如果状态是已发布，设置发布时间
        if ("PUBLISHED".equals(document.getStatus())) {
            document.setPublishedAt(LocalDateTime.now());
        }

        document = documentRepository.save(document);
        log.info("用户 {} 在空间 {} 创建文档: {}", userId, document.getSpaceId(), document.getId());

        return buildDocumentResponse(document);
    }

    /**
     * 更新文档
     */
    @Transactional
    @CacheEvict(value = {"kb-documents", "kb-document", "kb-space-stats"}, allEntries = true)
    public Map<String, Object> updateDocument(Long userId, Long documentId, Map<String, Object> request) {
        KbDocument document = documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));

        if (request.containsKey("title")) {
            document.setTitle(getString(request, "title"));
            document.setSlug(generateSlug(document.getTitle()));
        }

        if (request.containsKey("content")) {
            String content = getStringOrNull(request, "content");
            document.setContent(content);
            if (content != null && !content.isEmpty()) {
                document.setContentHtml(markdownToHtml(content));
                document.setWordCount(countWords(content));
                document.setReadingTimeMinutes(calculateReadingTime(content));
            }
        }

        if (request.containsKey("parentId")) {
            document.setParentId(getLongOrNull(request, "parentId"));
        }
        if (request.containsKey("sortOrder")) {
            document.setSortOrder(getInt(request, "sortOrder"));
        }
        if (request.containsKey("status")) {
            String newStatus = getString(request, "status");
            String oldStatus = document.getStatus();
            document.setStatus(newStatus);

            // 从草稿到发布，设置发布时间
            if ("PUBLISHED".equals(newStatus) && !"PUBLISHED".equals(oldStatus)) {
                document.setPublishedAt(LocalDateTime.now());
            }
        }
        if (request.containsKey("visibility")) {
            document.setVisibility(getString(request, "visibility"));
        }
        if (request.containsKey("coverImageUrl")) {
            document.setCoverImageUrl(getStringOrNull(request, "coverImageUrl"));
        }
        if (request.containsKey("tags")) {
            try {
                document.setTagsJson(objectMapper.writeValueAsString(request.get("tags")));
            } catch (Exception e) {
                log.error("序列化tags失败", e);
            }
        }

        document.setLastEditedBy(userId);

        document = documentRepository.save(document);
        log.info("用户 {} 更新文档: {}", userId, documentId);

        return buildDocumentResponse(document);
    }

    /**
     * 删除文档
     */
    @Transactional
    @CacheEvict(value = {"kb-documents", "kb-document", "kb-space-stats"}, allEntries = true)
    public void deleteDocument(Long userId, Long documentId) {
        KbDocument document = documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));

        documentRepository.delete(document);
        log.info("用户 {} 删除文档: {}", userId, documentId);
    }

    /**
     * 获取文档详情
     */
    @Cacheable(value = "kb-document", key = "#userId + ':' + #documentId")
    public Map<String, Object> getDocument(Long userId, Long documentId) {
        KbDocument document = documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));

        return buildDocumentResponse(document);
    }

    /**
     * 通过分享token获取文档（公开访问）
     */
    @Transactional
    public Map<String, Object> getDocumentByShareToken(String shareToken) {
        KbDocument document = documentRepository.findByShareToken(shareToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));

        if (!"PUBLIC".equals(document.getVisibility())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "文档不是公开的");
        }

        // 增加浏览次数
        document.setViewCount(document.getViewCount() + 1);
        documentRepository.save(document);

        return buildDocumentResponse(document);
    }

    /**
     * 获取空间的文档列表
     */
    @Cacheable(value = "kb-documents", key = "#userId + ':' + #spaceId + ':' + #status + ':' + #pageable.pageNumber")
    public Page<Map<String, Object>> getDocuments(Long userId, Long spaceId, String status, Pageable pageable) {
        Page<KbDocument> documents;
        if (status != null && !status.isEmpty()) {
            documents = documentRepository.findBySpaceIdAndStatusOrderBySortOrderAscIdDesc(spaceId, status, pageable);
        } else {
            documents = documentRepository.findBySpaceIdOrderBySortOrderAscIdDesc(spaceId, pageable);
        }

        return documents.map(this::buildDocumentListItem);
    }

    /**
     * 获取文档的子文档
     */
    public List<Map<String, Object>> getChildDocuments(Long userId, Long spaceId, Long parentId, String status) {
        List<KbDocument> documents = documentRepository
                .findBySpaceIdAndParentIdAndStatusOrderBySortOrderAsc(spaceId, parentId, status);

        return documents.stream()
                .map(this::buildDocumentListItem)
                .toList();
    }

    /**
     * 搜索文档
     */
    public Page<Map<String, Object>> searchDocuments(Long userId, Long spaceId, String keyword, Pageable pageable) {
        return documentRepository.searchInSpace(spaceId, userId, keyword, pageable)
                .map(this::buildDocumentListItem);
    }

    /**
     * 生成分享链接
     */
    @Transactional
    @CacheEvict(value = "kb-document", allEntries = true)
    public Map<String, Object> generateShareLink(Long userId, Long documentId) {
        KbDocument document = documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));

        if (document.getShareToken() == null || document.getShareToken().isEmpty()) {
            document.setShareToken(UUID.randomUUID().toString().replace("-", ""));
            documentRepository.save(document);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("shareToken", document.getShareToken());
        result.put("shareUrl", "/share/doc/" + document.getShareToken());
        return result;
    }

    /**
     * 复制文档
     */
    @Transactional
    @CacheEvict(value = {"kb-documents", "kb-space-stats"}, allEntries = true)
    public Map<String, Object> duplicateDocument(Long userId, Long documentId) {
        KbDocument source = documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));

        KbDocument duplicate = new KbDocument();
        duplicate.setUserId(userId);
        duplicate.setSpaceId(source.getSpaceId());
        duplicate.setTitle(source.getTitle() + " (副本)");
        duplicate.setContent(source.getContent());
        duplicate.setContentHtml(source.getContentHtml());
        duplicate.setSlug(generateSlug(duplicate.getTitle()));
        duplicate.setParentId(source.getParentId());
        duplicate.setSortOrder(source.getSortOrder());
        duplicate.setStatus("DRAFT");
        duplicate.setVisibility("PRIVATE");
        duplicate.setCoverImageUrl(source.getCoverImageUrl());
        duplicate.setTagsJson(source.getTagsJson());
        duplicate.setWordCount(source.getWordCount());
        duplicate.setReadingTimeMinutes(source.getReadingTimeMinutes());
        duplicate.setLastEditedBy(userId);

        duplicate = documentRepository.save(duplicate);
        log.info("用户 {} 复制文档 {} -> {}", userId, documentId, duplicate.getId());

        return buildDocumentResponse(duplicate);
    }

    /**
     * 获取热门文档
     */
    public Page<Map<String, Object>> getPopularDocuments(Long userId, Pageable pageable) {
        return documentRepository.findPopularDocuments(userId, pageable)
                .map(this::buildDocumentListItem);
    }

    /**
     * 获取统计信息
     */
    @Cacheable(value = "kb-space-stats", key = "#userId + ':' + #spaceId")
    public Map<String, Object> getStatistics(Long userId, Long spaceId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalDocuments", documentRepository.countBySpaceIdAndStatus(spaceId, null));
        stats.put("publishedDocuments", documentRepository.countBySpaceIdAndStatus(spaceId, "PUBLISHED"));
        stats.put("draftDocuments", documentRepository.countBySpaceIdAndStatus(spaceId, "DRAFT"));

        return stats;
    }

    // ==================== 私有辅助方法 ====================

    private Map<String, Object> buildDocumentResponse(KbDocument document) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", document.getId());
        result.put("spaceId", document.getSpaceId());
        result.put("userId", document.getUserId());
        result.put("title", document.getTitle());
        result.put("content", document.getContent());
        result.put("contentHtml", document.getContentHtml());
        result.put("slug", document.getSlug());
        result.put("parentId", document.getParentId());
        result.put("sortOrder", document.getSortOrder());
        result.put("status", document.getStatus());
        result.put("visibility", document.getVisibility());
        result.put("shareToken", document.getShareToken());
        result.put("coverImageUrl", document.getCoverImageUrl());
        result.put("tags", parseJson(document.getTagsJson()));
        result.put("viewCount", document.getViewCount());
        result.put("wordCount", document.getWordCount());
        result.put("readingTimeMinutes", document.getReadingTimeMinutes());
        result.put("lastEditedBy", document.getLastEditedBy());
        result.put("publishedAt", document.getPublishedAt());
        result.put("createdAt", document.getCreatedAt());
        result.put("updatedAt", document.getUpdatedAt());
        return result;
    }

    private Map<String, Object> buildDocumentListItem(KbDocument document) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", document.getId());
        result.put("title", document.getTitle());
        result.put("slug", document.getSlug());
        result.put("parentId", document.getParentId());
        result.put("status", document.getStatus());
        result.put("visibility", document.getVisibility());
        result.put("coverImageUrl", document.getCoverImageUrl());
        result.put("tags", parseJson(document.getTagsJson()));
        result.put("viewCount", document.getViewCount());
        result.put("wordCount", document.getWordCount());
        result.put("readingTimeMinutes", document.getReadingTimeMinutes());
        result.put("publishedAt", document.getPublishedAt());
        result.put("updatedAt", document.getUpdatedAt());
        return result;
    }

    private String markdownToHtml(String markdown) {
        try {
            org.commonmark.node.Node document = markdownParser.parse(markdown);
            return htmlRenderer.render(document);
        } catch (Exception e) {
            log.error("Markdown转换HTML失败", e);
            return markdown;
        }
    }

    private int countWords(String text) {
        if (text == null || text.isEmpty()) return 0;
        // 简单的中文字符和英文单词计数
        int chineseCount = text.replaceAll("[^\\u4e00-\\u9fa5]", "").length();
        int englishCount = text.split("\\s+").length;
        return chineseCount + englishCount;
    }

    private int calculateReadingTime(String text) {
        int wordCount = countWords(text);
        // 假设每分钟阅读400字
        return Math.max(1, (int) Math.ceil(wordCount / 400.0));
    }

    private String generateSlug(String title) {
        if (title == null || title.isEmpty()) {
            return UUID.randomUUID().toString().substring(0, 8);
        }
        // 简单的slug生成：转小写，替换空格为连字符
        String slug = title.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("^-+|-+$", "");

        // 如果太长，截断
        if (slug.length() > 100) {
            slug = slug.substring(0, 100);
        }

        // 添加随机后缀避免冲突
        return slug + "-" + UUID.randomUUID().toString().substring(0, 6);
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

    private Long getLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 不能为空");
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString());
    }

    private Long getLongOrNull(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString());
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
}
