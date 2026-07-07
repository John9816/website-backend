package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.PageView;
import com.example.website.dto.content.ContentArticleGenerateRequest;
import com.example.website.dto.content.ContentArticleUpdateRequest;
import com.example.website.dto.content.ContentArticleView;
import com.example.website.dto.content.ContentAutomationView;
import com.example.website.dto.content.ContentFactoryStatusView;
import com.example.website.dto.content.ContentHotTopicsView;
import com.example.website.dto.content.ContentStatusConfigView;
import com.example.website.entity.ContentArticle;
import com.example.website.repository.ContentArticleRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContentArticleService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_CATEGORY = "emotion_psychology";
    private static final String DEFAULT_LAYOUT = "clean";
    private static final String DEFAULT_IMAGE_MODE = "generate";

    private final ContentArticleRepository articleRepository;
    private final SysConfigService configService;
    private final ObjectMapper objectMapper;

    public ContentFactoryStatusView status() {
        String aiBase = config("ai.chat.baseUrl");
        String aiKey = config("ai.chat.apiKey");
        String imageBase = config("image.api.baseUrl");
        String imageKey = config("image.api.key");
        String appId = config("wechat.appId");
        String appSecret = config("wechat.appSecret");

        List<ContentStatusConfigView> configs = new ArrayList<>();
        configs.add(new ContentStatusConfigView("ai.chat.baseUrl", "文章模型地址", hasText(aiBase)));
        configs.add(new ContentStatusConfigView("ai.chat.apiKey", "文章模型密钥", hasText(aiKey)));
        configs.add(new ContentStatusConfigView("image.api.baseUrl", "封面图片地址", hasText(imageBase)));
        configs.add(new ContentStatusConfigView("image.api.key", "封面图片密钥", hasText(imageKey)));
        configs.add(new ContentStatusConfigView("wechat.appId", "公众号 AppID", hasText(appId)));
        configs.add(new ContentStatusConfigView("wechat.appSecret", "公众号 AppSecret", hasText(appSecret)));

        return new ContentFactoryStatusView(
                hasText(aiBase) && hasText(aiKey),
                hasText(imageBase) && hasText(imageKey),
                hasText(appId) && hasText(appSecret),
                configs
        );
    }

    public ContentHotTopicsView hotTopics(int limit, String category) {
        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(source("manual", "手动选题"));
        List<Map<String, Object>> items = new ArrayList<>();
        return new ContentHotTopicsView(LocalDateTime.now(), sources, items);
    }

    public PageView<ContentArticleView> list(Long userId, int page, int size) {
        Page<ContentArticle> result = articleRepository.findByUserIdOrderByUpdatedAtDescIdDesc(
                userId,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE))
        );
        return PageView.from(result, this::view);
    }

    public ContentArticleView get(Long userId, Long id) {
        return view(requireArticle(userId, id));
    }

    @Transactional
    public ContentArticleView generate(Long userId, ContentArticleGenerateRequest req) {
        ContentArticleGenerateRequest payload = req == null ? new ContentArticleGenerateRequest() : req;
        String topic = firstTopicTitle(payload);
        String category = defaultText(payload.getCategory(), DEFAULT_CATEGORY);
        String layoutTheme = defaultText(payload.getLayoutTheme(), DEFAULT_LAYOUT);
        String imageMode = defaultText(payload.getImageMode(), DEFAULT_IMAGE_MODE);
        String length = defaultText(payload.getLength(), "standard");
        String title = buildTitle(topic, category);
        String digest = buildDigest(topic, payload.getAudience());
        String markdown = buildMarkdown(topic, payload, length);
        String html = markdownToHtml(markdown);

        ContentArticle article = new ContentArticle();
        article.setUserId(userId);
        article.setTitle(title);
        article.setDigest(digest);
        article.setContentMarkdown(markdown);
        article.setContentHtml(html);
        article.setCoverPrompt(buildCoverPrompt(topic, payload.getCoverStyle(), category));
        article.setCoverImageUrl(null);
        article.setTopicsJson(writeJson(defaultTopics(payload, topic)));
        article.setTagsJson(writeJson(defaultTags(category)));
        article.setRiskTipsJson(writeJson(defaultRiskTips()));
        article.setModel(defaultText(payload.getModel(), config("ai.chat.defaultModel")));
        article.setCategory(category);
        article.setLayoutTheme(layoutTheme);
        article.setImageMode(imageMode);
        article.setAutomationJson(writeJson(ContentAutomationView.empty()));
        article.setStatus(ContentArticle.STATUS_DRAFT);

        ContentArticle saved = articleRepository.save(article);
        if (Boolean.TRUE.equals(payload.getAutoWechatDraft()) || Boolean.TRUE.equals(payload.getAutoPublish())) {
            saved = markWechatDraft(saved);
        }
        if (Boolean.TRUE.equals(payload.getAutoPublish())) {
            saved = markPublished(saved);
        }
        return view(saved);
    }

    @Transactional
    public ContentArticleView update(Long userId, Long id, ContentArticleUpdateRequest req) {
        ContentArticle article = requireArticle(userId, id);
        if (hasText(req.getTitle())) {
            article.setTitle(req.getTitle().trim());
        }
        article.setDigest(trimToNull(req.getDigest()));
        if (req.getContentMarkdown() != null) {
            article.setContentMarkdown(req.getContentMarkdown());
        }
        if (hasText(req.getContentHtml())) {
            article.setContentHtml(req.getContentHtml());
        }
        article.setCoverImageUrl(trimToNull(req.getCoverImageUrl()));
        article.setErrorMessage(null);
        return view(articleRepository.save(article));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        ContentArticle article = requireArticle(userId, id);
        articleRepository.delete(article);
    }

    @Transactional
    public Map<String, Object> createWechatDraft(Long userId, Long id) {
        ContentArticle article = markWechatDraft(requireArticle(userId, id));
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("mediaId", article.getWechatMediaId());
        draft.put("url", article.getWechatUrl());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("article", view(article));
        result.put("draft", draft);
        return result;
    }

    @Transactional
    public Map<String, Object> publishWechat(Long userId, Long id) {
        ContentArticle article = markWechatDraft(requireArticle(userId, id));
        article = markPublished(article);
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("mediaId", article.getWechatMediaId());
        draft.put("url", article.getWechatUrl());
        Map<String, Object> publish = new LinkedHashMap<>();
        publish.put("publishId", article.getWechatPublishId());
        publish.put("url", article.getWechatUrl());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("article", view(article));
        result.put("draft", draft);
        result.put("publish", publish);
        return result;
    }

    public ContentAutomationView automation(Long userId, Long articleId) {
        return ContentAutomationView.empty();
    }

    public ContentAutomationView retryAutomationJob(Long userId, String jobId) {
        return ContentAutomationView.empty();
    }

    private ContentArticle markWechatDraft(ContentArticle article) {
        article.setStatus(ContentArticle.STATUS_WECHAT_DRAFT);
        if (!hasText(article.getWechatMediaId())) {
            article.setWechatMediaId("local-draft-" + article.getId());
        }
        if (!hasText(article.getWechatUrl())) {
            article.setWechatUrl("/admin/content?articleId=" + article.getId());
        }
        article.setErrorMessage("Java 版内容工厂暂未接入微信草稿箱接口，已保留为本地草稿状态");
        return articleRepository.save(article);
    }

    private ContentArticle markPublished(ContentArticle article) {
        article.setStatus(ContentArticle.STATUS_PUBLISHED);
        if (!hasText(article.getWechatPublishId())) {
            article.setWechatPublishId("local-publish-" + article.getId());
        }
        if (!hasText(article.getWechatUrl())) {
            article.setWechatUrl("/admin/content?articleId=" + article.getId());
        }
        article.setErrorMessage("Java 版内容工厂暂未接入微信发布接口，已保留为本地发布状态");
        return articleRepository.save(article);
    }

    private ContentArticle requireArticle(Long userId, Long id) {
        return articleRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Article not found"));
    }

    private ContentArticleView view(ContentArticle article) {
        return ContentArticleView.from(
                article,
                readListOfMaps(article.getTopicsJson()),
                readListOfStrings(article.getTagsJson()),
                readListOfStrings(article.getRiskTipsJson()),
                readObject(article.getAutomationJson())
        );
    }

    private String firstTopicTitle(ContentArticleGenerateRequest payload) {
        String direct = trimToNull(payload.getTopic());
        if (direct != null) {
            return direct;
        }
        if (payload.getTopics() != null && !payload.getTopics().isEmpty()) {
            Object title = payload.getTopics().get(0).get("title");
            if (title != null && hasText(String.valueOf(title))) {
                return String.valueOf(title).trim();
            }
        }
        return "今日选题";
    }

    private List<Map<String, Object>> defaultTopics(ContentArticleGenerateRequest payload, String topic) {
        if (payload.getTopics() != null && !payload.getTopics().isEmpty()) {
            return payload.getTopics();
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "manual-" + UUID.randomUUID().toString());
        item.put("source", "manual");
        item.put("sourceName", "手动选题");
        item.put("rank", 1);
        item.put("title", topic);
        item.put("capturedAt", LocalDateTime.now().toString());
        return Collections.singletonList(item);
    }

    private List<String> defaultTags(String category) {
        List<String> tags = new ArrayList<>();
        tags.add("内容工厂");
        tags.add(category);
        return tags;
    }

    private List<String> defaultRiskTips() {
        return Collections.singletonList("Java 版内容工厂当前为最小可用草稿生成，发布前请人工复核事实、标题和配图。");
    }

    private String buildTitle(String topic, String category) {
        return topic.length() > 80 ? topic.substring(0, 80) : topic;
    }

    private String buildDigest(String topic, String audience) {
        String who = hasText(audience) ? audience.trim() : "目标读者";
        return "围绕「" + topic + "」生成的草稿，面向" + who + "，用于迁移期内容工厂占位与人工编辑。";
    }

    private String buildMarkdown(String topic, ContentArticleGenerateRequest payload, String length) {
        List<String> lines = new ArrayList<>();
        lines.add("# " + topic);
        lines.add("");
        lines.add("## 选题判断");
        lines.add(defaultText(payload.getAngle(), "这个选题值得从现实处境、情绪需求和长期判断三个层面展开。"));
        lines.add("");
        lines.add("## 核心内容");
        lines.add("这是一篇由 Java 版内容工厂生成的迁移期草稿。当前版本先保证文章管理、编辑、状态流转和接口兼容，AI 深度生成与微信发布将继续迁移。");
        lines.add("");
        lines.add("## 写作口吻");
        lines.add(defaultText(payload.getTone(), "克制、清晰、有温度。"));
        lines.add("");
        lines.add("## 人工复核");
        lines.add("请在发布前补充事实来源、配图和最终标题。篇幅偏好：" + length + "。");
        return lines.stream().collect(Collectors.joining("\n"));
    }

    private String markdownToHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        for (String line : markdown.split("\\r?\\n")) {
            if (line.startsWith("# ")) {
                html.append("<h1>").append(escape(line.substring(2))).append("</h1>");
            } else if (line.startsWith("## ")) {
                html.append("<h2>").append(escape(line.substring(3))).append("</h2>");
            } else if (line.trim().isEmpty()) {
                html.append("\n");
            } else {
                html.append("<p>").append(escape(line)).append("</p>");
            }
        }
        return html.toString();
    }

    private String buildCoverPrompt(String topic, String style, String category) {
        return "公众号封面图，主题：" + topic + "，栏目：" + category + "，风格：" + defaultText(style, "干净、清晰、有传播感");
    }

    private Map<String, Object> source(String id, String name) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", id);
        source.put("name", name);
        return source;
    }

    private String config(String key) {
        return configService.getValue(key).orElse("");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(500, "Failed to serialize content payload");
        }
    }

    private List<Map<String, Object>> readListOfMaps(String json) {
        if (!hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<String> readListOfStrings(String json) {
        if (!hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Object readObject(String json) {
        if (!hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String defaultText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean hasText(String value) {
        return trimToNull(value) != null;
    }

    private String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
