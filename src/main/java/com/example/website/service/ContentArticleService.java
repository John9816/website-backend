package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.ImageGenerateRequest;
import com.example.website.dto.ImageGenerationsResponse;
import com.example.website.dto.PageView;
import com.example.website.dto.content.ContentAgentRunRequest;
import com.example.website.dto.content.ContentAgentRunResult;
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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContentArticleService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_CATEGORY = "科技 / 互联网";
    private static final String DEFAULT_LAYOUT = "clean";
    private static final String DEFAULT_IMAGE_MODE = "generate";
    private static final String NOWHOTS_API_BASE = "https://api.nowhots.com/";
    private static final Path CONTENT_ASSET_DIR = Paths.get("uploads", "content-assets");

    private final ContentArticleRepository articleRepository;
    private final SysConfigService configService;
    private final ObjectMapper objectMapper;
    private final WechatOfficialAccountClient wechatClient;
    private final AiChatUpstreamClient aiChatUpstreamClient;
    private final ImageService imageService;
    @Qualifier(com.example.website.config.OkHttpConfig.CLIENT_QUICK)
    private final OkHttpClient okHttpClient;

    public ContentFactoryStatusView status() {
        String aiBase = config("ai.chat.baseUrl");
        String aiKey = config("ai.chat.apiKey");
        String imageBase = config("image.api.baseUrl");
        String imageKey = config("image.api.key");
        String appId = config("wechat.appId");
        String appSecret = config("wechat.appSecret");
        String coverMediaId = config("wechat.coverMediaId");
        String freePublishEnabled = config("wechat.freePublishEnabled");

        List<ContentStatusConfigView> configs = new ArrayList<>();
        configs.add(new ContentStatusConfigView("ai.chat.baseUrl", "文章模型地址", hasText(aiBase)));
        configs.add(new ContentStatusConfigView("ai.chat.apiKey", "文章模型密钥", hasText(aiKey)));
        configs.add(new ContentStatusConfigView("image.api.baseUrl", "封面图片地址", hasText(imageBase)));
        configs.add(new ContentStatusConfigView("image.api.key", "封面图片密钥", hasText(imageKey)));
        configs.add(new ContentStatusConfigView("wechat.appId", "公众号 AppID", hasText(appId)));
        configs.add(new ContentStatusConfigView("wechat.appSecret", "公众号 AppSecret", hasText(appSecret)));
        configs.add(new ContentStatusConfigView("wechat.coverMediaId", "默认微信封面素材", hasText(coverMediaId)));
        configs.add(new ContentStatusConfigView("wechat.freePublishEnabled", "微信一键发布权限", "true".equalsIgnoreCase(freePublishEnabled)));

        return new ContentFactoryStatusView(
                hasText(aiBase) && hasText(aiKey),
                hasText(imageBase) && hasText(imageKey),
                hasText(appId) && hasText(appSecret),
                configs
        );
    }

    public ContentHotTopicsView hotTopics(int limit, String category) {
        int perSourceLimit = Math.min(Math.max(limit, 1), 30);
        String normalizedCategory = normalizeCategory(category);
        List<HotSource> hotSources = hotSources(normalizedCategory);
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(Math.max(hotSources.size(), 1), 8));
        List<Map<String, Object>> items;
        try {
            List<CompletableFuture<List<Map<String, Object>>>> tasks = hotSources.stream()
                    .map(hotSource -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return fetchHotSource(hotSource, perSourceLimit);
                        } catch (Exception ignored) {
                            // Keep the page usable when a public hot-list source is temporarily unavailable.
                            return Collections.<Map<String, Object>>emptyList();
                        }
                    }, executor))
                    .collect(Collectors.toList());
            items = tasks.stream()
                    .flatMap(task -> task.join().stream())
                    .collect(Collectors.toList());
        } finally {
            executor.shutdownNow();
        }
        items = rankHotTopics(items, normalizedCategory, perSourceLimit);
        if (items.isEmpty()) {
            items = fallbackHotTopics(normalizedCategory);
        }
        List<Map<String, Object>> sources = hotSources.stream()
                .map(item -> source(item.getId(), item.getName()))
                .collect(Collectors.toList());
        return new ContentHotTopicsView(LocalDateTime.now(), sources, items);
    }

    @Transactional
    public ContentAgentRunResult runAgent(Long userId, ContentAgentRunRequest req) {
        ContentAgentRunRequest payload = req == null ? new ContentAgentRunRequest() : req;
        String category = normalizeCategory(payload.getCategory());
        Map<String, Object> selectedTopic = selectAgentTopic(payload, category);

        ContentArticleGenerateRequest generateRequest = new ContentArticleGenerateRequest();
        generateRequest.setTopic(asString(selectedTopic.get("title")));
        generateRequest.setCategory(category);
        generateRequest.setAngle(defaultText(asString(selectedTopic.get("angle")), defaultAgentAngle(category)));
        generateRequest.setAudience(defaultText(asString(selectedTopic.get("audience")), "想快速看懂热点、不想被标题带节奏的读者"));
        generateRequest.setTone("像朋友聊天，口语化，有判断但不端着，拒绝正式、学术和研报腔");
        generateRequest.setLength(defaultText(payload.getLength(), "standard"));
        generateRequest.setLayoutTheme(DEFAULT_LAYOUT);
        generateRequest.setImageMode(DEFAULT_IMAGE_MODE);
        generateRequest.setResearchEnabled(true);
        generateRequest.setResearchDepth("standard");
        generateRequest.setGenerateCover(Boolean.TRUE.equals(payload.getGenerateCover()));
        generateRequest.setAutoWechatDraft(false);
        generateRequest.setAutoPublish(false);
        generateRequest.setTopics(Collections.singletonList(selectedTopic));

        ContentArticleView generated = generate(userId, generateRequest);
        ContentArticle article = requireArticle(userId, generated.getId());
        if (!Boolean.FALSE.equals(payload.getAutoWechatDraft())) {
            article = createWechatDraftArticle(article);
        }
        ContentAutomationView automation = buildAgentAutomation(article, selectedTopic);
        article.setAutomationJson(writeJson(automation));
        article = articleRepository.save(article);

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("mediaId", article.getWechatMediaId());
        draft.put("url", article.getWechatUrl());
        draft.put("mode", article.getWechatMediaId() != null && article.getWechatMediaId().startsWith("local-") ? "local" : "wechat");
        return new ContentAgentRunResult(view(article), automation, selectedTopic, draft);
    }

    public PageView<ContentArticleView> list(Long userId, int page, int size) {
        Page<ContentArticleRepository.ContentArticleSummary> result = articleRepository.findSummariesByUserId(
                userId,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE))
        );
        return PageView.from(result, this::summaryView);
    }

    public ContentArticleView get(Long userId, Long id) {
        return view(requireArticle(userId, id));
    }

    @Transactional
    public ContentArticleView generate(Long userId, ContentArticleGenerateRequest req) {
        ContentArticleGenerateRequest payload = req == null ? new ContentArticleGenerateRequest() : req;
        String topic = firstTopicTitle(payload);
        String category = normalizeCategory(payload.getCategory());
        String layoutTheme = defaultText(payload.getLayoutTheme(), DEFAULT_LAYOUT);
        String imageMode = defaultText(payload.getImageMode(), DEFAULT_IMAGE_MODE);
        String length = defaultText(payload.getLength(), "standard");
        ContentGenerationResult generated = generateArticleContent(topic, category, payload, length);
        String coverPrompt = defaultText(generated.getCoverPrompt(), buildCoverPrompt(topic, payload.getCoverStyle(), category));
        String coverImageUrl = generateCoverImageIfNeeded(coverPrompt, imageMode, payload.getGenerateCover(), generated.getRiskTips());

        ContentArticle article = new ContentArticle();
        article.setUserId(userId);
        article.setTitle(buildTitle(generated.getTitle(), category));
        article.setDigest(generated.getDigest());
        article.setContentMarkdown(generated.getMarkdown());
        article.setContentHtml(markdownToHtml(generated.getMarkdown()));
        article.setCoverPrompt(coverPrompt);
        article.setCoverImageUrl(coverImageUrl);
        article.setTopicsJson(writeJson(defaultTopics(payload, topic)));
        article.setTagsJson(writeJson(generated.getTags().isEmpty() ? defaultTags(category) : generated.getTags()));
        article.setRiskTipsJson(writeJson(generated.getRiskTips()));
        article.setModel(generated.getModel());
        article.setCategory(category);
        article.setLayoutTheme(layoutTheme);
        article.setImageMode(imageMode);
        article.setAutomationJson(writeJson(ContentAutomationView.empty()));
        article.setStatus(ContentArticle.STATUS_DRAFT);

        ContentArticle saved = articleRepository.save(article);
        try {
            if (Boolean.TRUE.equals(payload.getAutoWechatDraft()) || Boolean.TRUE.equals(payload.getAutoPublish())) {
                saved = createWechatDraftArticle(saved);
            }
            if (Boolean.TRUE.equals(payload.getAutoPublish())) {
                saved = publishWechatArticle(saved);
            }
        } catch (BusinessException ignored) {
            saved = articleRepository.findById(saved.getId()).orElse(saved);
        }
        return view(saved);
    }

    @Transactional
    public ContentArticleView update(Long userId, Long id, ContentArticleUpdateRequest req) {
        ContentArticle article = requireArticle(userId, id);
        if (hasText(req.getTitle())) {
            article.setTitle(req.getTitle().trim());
        }
        if (req.getCategory() != null) {
            article.setCategory(normalizeCategory(req.getCategory()));
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

    public Map<String, Object> createWechatDraft(Long userId, Long id) {
        ContentArticle article = createWechatDraftArticle(requireArticle(userId, id));
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("mediaId", article.getWechatMediaId());
        draft.put("url", article.getWechatUrl());
        draft.put("mode", article.getWechatMediaId() != null && article.getWechatMediaId().startsWith("local-") ? "local" : "wechat");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("article", view(article));
        result.put("draft", draft);
        return result;
    }

    public Map<String, Object> publishWechat(Long userId, Long id) {
        if (!freePublishEnabled()) {
            throw new BusinessException(403, "当前公众号是订阅号，未启用 freepublish 权限，请先创建微信草稿后在公众平台人工发布");
        }
        ContentArticle article = publishWechatArticle(requireArticle(userId, id));
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("mediaId", article.getWechatMediaId());
        draft.put("url", article.getWechatUrl());
        draft.put("mode", article.getWechatMediaId() != null && article.getWechatMediaId().startsWith("local-") ? "local" : "wechat");
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
        if (articleId == null) {
            return ContentAutomationView.empty();
        }
        ContentArticle article = requireArticle(userId, articleId);
        Object automation = readObject(article.getAutomationJson());
        if (automation instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) automation;
            return new ContentAutomationView(
                    asString(map.get("currentStage")),
                    asList(map.get("logs")),
                    asList(map.get("jobs")),
                    asList(map.get("publishRecords"))
            );
        }
        return buildAutomation(article, article.getErrorMessage() == null ? null : article.getErrorMessage());
    }

    public ContentAutomationView retryAutomationJob(Long userId, String jobId) {
        return ContentAutomationView.empty();
    }

    private ContentArticle createWechatDraftArticle(ContentArticle article) {
        String appId = config(WechatOfficialAccountClient.CFG_APP_ID);
        String appSecret = config(WechatOfficialAccountClient.CFG_APP_SECRET);
        if (!wechatClient.configured(appId, appSecret)) {
            return markLocalDraft(article, "缺少 wechat.appId / wechat.appSecret，已保留为本站本地草稿");
        }
        try {
            String thumbMediaId = resolveThumbMediaId(article, appId, appSecret);
            WechatOfficialAccountClient.DraftResult draft = wechatClient.addDraft(
                    appId,
                    appSecret,
                    article.getTitle(),
                    article.getDigest(),
                    article.getContentHtml(),
                    thumbMediaId,
                    config(WechatOfficialAccountClient.CFG_AUTHOR),
                    config(WechatOfficialAccountClient.CFG_SOURCE_URL)
            );
            article.setStatus(ContentArticle.STATUS_WECHAT_DRAFT);
            article.setWechatMediaId(draft.getMediaId());
            article.setWechatUrl(null);
            article.setErrorMessage(null);
            article.setAutomationJson(writeJson(buildAutomation(article, null)));
            return articleRepository.save(article);
        } catch (BusinessException e) {
            markWechatFailure(article, e.getMessage(), "wechat_draft");
            return markLocalDraft(article, "微信草稿创建失败，已保留为本站本地草稿：" + e.getMessage());
        }
    }

    private ContentArticle publishWechatArticle(ContentArticle article) {
        if (!freePublishEnabled()) {
            throw new BusinessException(403, "当前公众号未启用 freepublish 权限，请先创建微信草稿后在公众平台人工发布");
        }
        if (!hasText(article.getWechatMediaId()) || article.getWechatMediaId().startsWith("local-")) {
            article = createWechatDraftArticle(article);
        }
        String appId = config(WechatOfficialAccountClient.CFG_APP_ID);
        String appSecret = config(WechatOfficialAccountClient.CFG_APP_SECRET);
        try {
            WechatOfficialAccountClient.PublishResult publish = wechatClient.submitPublish(appId, appSecret, article.getWechatMediaId());
            article.setStatus(ContentArticle.STATUS_PUBLISHED);
            article.setWechatPublishId(publish.getPublishId());
            article.setWechatUrl(null);
            article.setErrorMessage(null);
            article.setAutomationJson(writeJson(buildAutomation(article, null)));
            return articleRepository.save(article);
        } catch (BusinessException e) {
            markWechatFailure(article, e.getMessage(), "publish");
            throw e;
        }
    }

    private ContentArticle markLocalDraft(ContentArticle article, String message) {
        article.setStatus(ContentArticle.STATUS_WECHAT_DRAFT);
        if (!hasText(article.getWechatMediaId())) {
            article.setWechatMediaId("local-draft-" + article.getId());
        }
        if (!hasText(article.getWechatUrl())) {
            article.setWechatUrl("/admin/content?articleId=" + article.getId());
        }
        article.setErrorMessage(message);
        article.setAutomationJson(writeJson(buildAutomation(article, message)));
        return articleRepository.save(article);
    }

    private void markWechatFailure(ContentArticle article, String message, String stage) {
        article.setErrorMessage(message);
        article.setAutomationJson(writeJson(buildAutomation(article, message, stage)));
        articleRepository.save(article);
    }

    private String resolveThumbMediaId(ContentArticle article, String appId, String appSecret) {
        byte[] coverBytes = readCoverBytes(article.getCoverImageUrl());
        if (coverBytes != null && coverBytes.length > 0) {
            return wechatClient.uploadPermanentImage(appId, appSecret, coverBytes, coverFilename(article.getCoverImageUrl())).getMediaId();
        }
        String configured = trimToNull(config("wechat.coverMediaId"));
        if (configured != null) {
            return configured;
        }
        WechatOfficialAccountClient.MaterialResult uploaded = wechatClient.uploadPermanentImage(
                appId,
                appSecret,
                generateDefaultCoverBytes(article),
                "zaoyibu-default-cover.png"
        );
        configService.upsertByKey("wechat.coverMediaId", uploaded.getMediaId(), "Default WeChat cover media_id uploaded by content factory.");
        return uploaded.getMediaId();
    }

    private byte[] readCoverBytes(String coverImageUrl) {
        String url = trimToNull(coverImageUrl);
        if (url == null) {
            return null;
        }
        try {
            if (url.startsWith("/api/v1/content/assets/")) {
                return readLocalFile(CONTENT_ASSET_DIR, url.substring("/api/v1/content/assets/".length()));
            }
            if (url.startsWith("/api/v1/image/file/")) {
                String uploadDir = defaultText(config(ImageService.CFG_UPLOAD_DIR), "uploads/images");
                return readLocalFile(Paths.get(uploadDir), url.substring("/api/v1/image/file/".length()));
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                Request request = new Request.Builder().url(url).get().build();
                try (Response response = okHttpClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new BusinessException(502, "下载文章封面失败：HTTP " + response.code());
                    }
                    return response.body().bytes();
                }
            }
            return null;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(500, "读取文章封面失败：" + e.getMessage());
        }
    }

    private byte[] readLocalFile(Path baseDir, String filename) throws IOException {
        if (filename == null || !filename.matches("^[A-Za-z0-9._-]+$")) {
            throw new BusinessException(400, "文章封面文件名不合法");
        }
        Path base = baseDir.normalize();
        Path file = base.resolve(filename).normalize();
        if (!file.startsWith(base) || !Files.exists(file)) {
            throw new BusinessException(404, "文章封面文件不存在");
        }
        return Files.readAllBytes(file);
    }

    private String coverFilename(String coverImageUrl) {
        String value = defaultText(coverImageUrl, "cover.png");
        int slash = value.lastIndexOf('/');
        String filename = slash >= 0 ? value.substring(slash + 1) : value;
        int query = filename.indexOf('?');
        if (query >= 0) {
            filename = filename.substring(0, query);
        }
        return hasText(filename) ? filename : "cover.png";
    }

    private byte[] generateDefaultCoverBytes(ContentArticle article) {
        int width = 900;
        int height = 500;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(248, 250, 252));
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(22, 101, 52));
            g.fillRect(0, 0, 18, height);
            g.setColor(new Color(15, 23, 42));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 54));
            drawWrappedText(g, defaultText(article.getTitle(), "早一步信息差"), 72, 130, 760, 68, 3);
            g.setColor(new Color(71, 85, 105));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 28));
            drawWrappedText(g, defaultText(article.getCategory(), "热点解读"), 74, 360, 760, 38, 1);
            g.setColor(new Color(22, 101, 52));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            g.drawString("早一步信息差", 72, 430);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(500, "生成默认微信封面失败：" + e.getMessage());
        }
    }

    private void drawWrappedText(Graphics2D g, String text, int x, int y, int maxWidth, int lineHeight, int maxLines) {
        FontMetrics metrics = g.getFontMetrics();
        String normalized = defaultText(text, "").replaceAll("\\s+", " ");
        StringBuilder line = new StringBuilder();
        int lines = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            String candidate = line.toString() + ch;
            if (metrics.stringWidth(candidate) > maxWidth && line.length() > 0) {
                lines++;
                g.drawString(line.toString(), x, y + (lines - 1) * lineHeight);
                if (lines >= maxLines) {
                    return;
                }
                line.setLength(0);
            }
            line.append(ch);
        }
        if (line.length() > 0 && lines < maxLines) {
            g.drawString(line.toString(), x, y + lines * lineHeight);
        }
    }

    private ContentAutomationView buildAutomation(ContentArticle article, String errorMessage) {
        return buildAutomation(article, errorMessage, article.getStatus().equals(ContentArticle.STATUS_PUBLISHED) ? "publish" : "wechat_draft");
    }

    private ContentAutomationView buildAutomation(ContentArticle article, String errorMessage, String currentStage) {
        String now = LocalDateTime.now().toString();
        List<Object> logs = new ArrayList<>();
        logs.add(logItem(article.getId() + "-generate", "generate", "SUCCESS", "文章内容已生成", null, valueOrNow(article.getCreatedAt(), now)));
        if (ContentArticle.STATUS_WECHAT_DRAFT.equals(article.getStatus()) || ContentArticle.STATUS_PUBLISHED.equals(article.getStatus()) || "wechat_draft".equals(currentStage)) {
            logs.add(logItem(article.getId() + "-wechat-draft", "wechat_draft", errorMessage == null ? "SUCCESS" : "FAILED",
                    errorMessage == null ? "微信草稿已创建" : "微信草稿创建失败", errorMessage, now));
        }
        if (ContentArticle.STATUS_PUBLISHED.equals(article.getStatus()) || "publish".equals(currentStage)) {
            logs.add(logItem(article.getId() + "-publish", "publish", errorMessage == null ? "SUCCESS" : "FAILED",
                    errorMessage == null ? "微信发布已提交" : "微信发布提交失败", errorMessage, now));
        }

        List<Object> jobs = new ArrayList<>();
        jobs.add(jobItem(article.getId() + "-wechat-draft-job", "wechat_draft",
                errorMessage != null && "wechat_draft".equals(currentStage) ? "FAILED" : "SUCCESS", errorMessage, now));
        if (ContentArticle.STATUS_PUBLISHED.equals(article.getStatus()) || "publish".equals(currentStage)) {
            jobs.add(jobItem(article.getId() + "-publish-job", "publish",
                    errorMessage != null && "publish".equals(currentStage) ? "FAILED" : "SUCCESS", errorMessage, now));
        }

        List<Object> records = new ArrayList<>();
        if (hasText(article.getWechatMediaId()) || hasText(article.getWechatPublishId())) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", article.getId() + "-wechat");
            record.put("action", ContentArticle.STATUS_PUBLISHED.equals(article.getStatus()) ? "publish" : "draft");
            record.put("status", errorMessage == null ? "SUCCESS" : "FAILED");
            record.put("mediaId", article.getWechatMediaId());
            record.put("publishId", article.getWechatPublishId());
            record.put("url", article.getWechatUrl());
            record.put("errorMessage", errorMessage);
            record.put("createdAt", now);
            records.add(record);
        }
        return new ContentAutomationView(currentStage, logs, jobs, records);
    }

    private Map<String, Object> logItem(String id, String stage, String status, String message, String detail, String createdAt) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("stage", stage);
        item.put("status", status);
        item.put("message", message);
        item.put("detail", detail);
        item.put("createdAt", createdAt);
        return item;
    }

    private Map<String, Object> jobItem(String id, String stage, String status, String errorMessage, String now) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("stage", stage);
        item.put("status", status);
        item.put("attempts", 1);
        item.put("maxAttempts", 1);
        item.put("errorMessage", errorMessage);
        item.put("createdAt", now);
        item.put("updatedAt", now);
        return item;
    }

    private String valueOrNow(LocalDateTime value, String now) {
        return value == null ? now : value.toString();
    }

    private Map<String, Object> selectAgentTopic(ContentAgentRunRequest req, String category) {
        String manualTopic = trimToNull(req.getTopic());
        if (manualTopic != null) {
            Map<String, Object> topic = fallbackAgentTopic(category, req.getInstruction());
            topic.put("title", manualTopic);
            topic.put("source", "agent_manual");
            topic.put("sourceName", "Agent 手动主题");
            return topic;
        }
        if (hasText(config("ai.chat.baseUrl"))) {
            try {
                List<AiChatUpstreamClient.ChatMessage> messages = new ArrayList<>();
                messages.add(new AiChatUpstreamClient.ChatMessage("system",
                        "你是公众号「早一步信息差」的自动选题 Agent。只输出 JSON，不要 Markdown 代码块。"));
                messages.add(new AiChatUpstreamClient.ChatMessage("user", buildTopicPrompt(category, req.getInstruction())));
                AiChatUpstreamClient.ChatCompletionResult result = aiChatUpstreamClient.completeQuick(
                        config("ai.chat.baseUrl"),
                        config("ai.chat.apiKey"),
                        defaultText(config("ai.chat.defaultModel"), "mimo-v2.5-pro"),
                        messages
                );
                Map<String, Object> parsed = tryReadMap(extractJsonObject(result.getContent()));
                if (parsed != null && hasText(asString(parsed.get("title")))) {
                    return normalizeAgentTopic(parsed, category);
                }
            } catch (Exception ignored) {
                // Fallback below keeps the automation agent usable when topic selection times out.
            }
        }
        return fallbackAgentTopic(category, req.getInstruction());
    }

    private String buildTopicPrompt(String category, String instruction) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请为订阅号「早一步信息差」自动选择 1 个今天值得写的公众号选题。\n");
        prompt.append("账号定位：信息差型热点解读。栏目：").append(category).append("。\n");
        prompt.append("偏好：口语化，像朋友聊天；拒绝正式、学术、研报腔。\n");
        prompt.append("参考方向：科技 / 互联网、教育 / 职场、财政金融，以及普通人容易忽略的信息差。\n");
        if (hasText(instruction)) {
            prompt.append("额外要求：").append(instruction.trim()).append("\n");
        }
        prompt.append("请输出 JSON：title,summary,angle,audience,tags,reason。tags 是字符串数组。\n");
        prompt.append("选题要具体，不要写成泛泛的行业观察。");
        return prompt.toString();
    }

    private Map<String, Object> normalizeAgentTopic(Map<String, Object> parsed, String category) {
        Map<String, Object> topic = new LinkedHashMap<>();
        topic.put("id", "agent-" + UUID.randomUUID().toString());
        topic.put("source", "agent");
        topic.put("sourceName", "自动选题 Agent");
        topic.put("rank", 1);
        topic.put("title", defaultText(asString(parsed.get("title")), fallbackTopicTitle(category)));
        topic.put("summary", trimToNull(asString(parsed.get("summary"))));
        topic.put("angle", defaultText(asString(parsed.get("angle")), defaultAgentAngle(category)));
        topic.put("audience", defaultText(asString(parsed.get("audience")), "想快速看懂热点、不想被标题带节奏的读者"));
        topic.put("reason", trimToNull(asString(parsed.get("reason"))));
        topic.put("tags", readStringList(parsed.get("tags")));
        topic.put("capturedAt", LocalDateTime.now().toString());
        return topic;
    }

    private Map<String, Object> fallbackAgentTopic(String category, String instruction) {
        Map<String, Object> topic = new LinkedHashMap<>();
        topic.put("id", "agent-fallback-" + UUID.randomUUID().toString());
        topic.put("source", "agent_fallback");
        topic.put("sourceName", "自动选题 Agent");
        topic.put("rank", 1);
        topic.put("title", fallbackTopicTitle(category));
        topic.put("summary", "围绕今天读者可能忽略的信息差，生成一篇可编辑的公众号草稿。");
        topic.put("angle", hasText(instruction) ? instruction.trim() : defaultAgentAngle(category));
        topic.put("audience", "想快速看懂热点、不想被标题带节奏的读者");
        topic.put("reason", "AI 选题不可用或超时，使用本地兜底选题保证自动化链路继续运行。");
        topic.put("tags", defaultTags(category));
        topic.put("capturedAt", LocalDateTime.now().toString());
        return topic;
    }

    private String fallbackTopicTitle(String category) {
        String day = LocalDate.now().toString();
        if (category != null && category.contains("教育")) {
            return day + " 职场和教育里的新信息差，普通人该先看懂什么";
        }
        if (category != null && category.contains("财政")) {
            return day + " 财经热点背后的信息差，和普通人的钱包有什么关系";
        }
        return day + " 科技互联网热点背后的信息差，普通人别只看标题";
    }

    private String defaultAgentAngle(String category) {
        if (category != null && category.contains("教育")) {
            return "从升学、就业、转行和职场选择里的具体成本切入，讲清楚普通人该注意什么";
        }
        if (category != null && category.contains("财政")) {
            return "先翻译政策、市场和公司新闻里的关键信息差，再落到钱包、工作和决策影响";
        }
        return "先讲热点和普通人有什么关系，再拆平台、产品、公司和行业里的信息差";
    }

    private ContentAutomationView buildAgentAutomation(ContentArticle article, Map<String, Object> topic) {
        String now = LocalDateTime.now().toString();
        String error = article.getErrorMessage();
        boolean localDraft = article.getWechatMediaId() != null && article.getWechatMediaId().startsWith("local-");
        boolean draftOk = hasText(article.getWechatMediaId()) && !localDraft && !hasText(error);

        List<Object> logs = new ArrayList<>();
        logs.add(logItem(article.getId() + "-agent-topic", "topic", "SUCCESS",
                "已自动选题：" + defaultText(asString(topic.get("title")), article.getTitle()),
                asString(topic.get("reason")), now));
        logs.add(logItem(article.getId() + "-agent-research", "research", "SUCCESS",
                "已生成选题角度、读者画像和写作方向", asString(topic.get("summary")), now));
        logs.add(logItem(article.getId() + "-agent-generate", "generate", "SUCCESS",
                "已自动编写正文并转换为公众号 HTML", null, valueOrNow(article.getCreatedAt(), now)));
        logs.add(logItem(article.getId() + "-agent-review", "review", article.getRiskTipsJson() == null ? "SKIPPED" : "SUCCESS",
                "已生成发布前复核提示", null, now));
        logs.add(logItem(article.getId() + "-agent-wechat-draft", "wechat_draft", draftOk ? "SUCCESS" : (localDraft ? "FAILED" : "PENDING"),
                draftOk ? "已创建微信草稿" : (localDraft ? "微信草稿失败，已保留本地草稿" : "等待创建微信草稿"),
                error, now));

        List<Object> jobs = new ArrayList<>();
        jobs.add(jobItem(article.getId() + "-agent-run", "generate", "SUCCESS", null, now));
        jobs.add(jobItem(article.getId() + "-agent-wechat-draft-job", "wechat_draft", draftOk ? "SUCCESS" : (localDraft ? "FAILED" : "PENDING"), error, now));

        List<Object> records = new ArrayList<>();
        if (hasText(article.getWechatMediaId())) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", article.getId() + "-agent-wechat");
            record.put("action", "draft");
            record.put("status", draftOk ? "SUCCESS" : "FAILED");
            record.put("mediaId", article.getWechatMediaId());
            record.put("publishId", article.getWechatPublishId());
            record.put("url", article.getWechatUrl());
            record.put("errorMessage", error);
            record.put("createdAt", now);
            records.add(record);
        }
        return new ContentAutomationView("wechat_draft", logs, jobs, records);
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

    private ContentArticleView summaryView(ContentArticleRepository.ContentArticleSummary article) {
        ContentArticleView view = new ContentArticleView();
        view.setId(article.getId());
        view.setTitle(article.getTitle());
        view.setDigest(article.getDigest());
        view.setContentMarkdown("");
        view.setContentHtml("");
        view.setCoverPrompt(article.getCoverPrompt());
        view.setCoverImageUrl(article.getCoverImageUrl());
        view.setTopics(Collections.emptyList());
        view.setTags(Collections.emptyList());
        view.setRiskTips(Collections.emptyList());
        view.setModel(article.getModel());
        view.setCategory(article.getCategory());
        view.setLayoutTheme(article.getLayoutTheme());
        view.setImageMode(article.getImageMode());
        view.setAutomation(null);
        view.setStatus(article.getStatus());
        view.setWechatMediaId(article.getWechatMediaId());
        view.setWechatPublishId(article.getWechatPublishId());
        view.setWechatUrl(article.getWechatUrl());
        view.setErrorMessage(article.getErrorMessage());
        view.setCreatedAt(article.getCreatedAt());
        view.setUpdatedAt(article.getUpdatedAt());
        return view;
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
        return Collections.singletonList("早一步信息差为订阅号，当前仅生成微信草稿；发布前请人工复核事实、标题、配图和平台规则。");
    }

    private ContentGenerationResult generateArticleContent(String topic,
                                                           String category,
                                                           ContentArticleGenerateRequest payload,
                                                           String length) {
        String model = defaultText(payload.getModel(), config("ai.chat.defaultModel"));
        List<String> riskTips = new ArrayList<>(defaultRiskTips());
        if (!hasText(config("ai.chat.baseUrl"))) {
            riskTips.add("文章模型地址未配置，已使用本地模板生成。");
            return fallbackGeneration(topic, category, payload, length, model, riskTips);
        }
        try {
            String prompt = buildArticlePrompt(topic, category, payload, length);
            List<AiChatUpstreamClient.ChatMessage> messages = new ArrayList<>();
            messages.add(new AiChatUpstreamClient.ChatMessage("system",
                    "你是公众号「早一步信息差」的选题和写作助手。只输出 JSON，不要 Markdown 代码块。"));
            messages.add(new AiChatUpstreamClient.ChatMessage("user", prompt));
            AiChatUpstreamClient.ChatCompletionResult result = aiChatUpstreamClient.complete(
                    config("ai.chat.baseUrl"),
                    config("ai.chat.apiKey"),
                    model,
                    messages
            );
            ContentGenerationResult parsed = parseGenerationResult(result.getContent(), topic, category, payload, length, result.getModel());
            parsed.getRiskTips().addAll(0, riskTips);
            return parsed;
        } catch (Exception e) {
            riskTips.add("AI 正文生成失败，已回退本地模板：" + e.getMessage());
            return fallbackGeneration(topic, category, payload, length, model, riskTips);
        }
    }

    private String buildArticlePrompt(String topic, String category, ContentArticleGenerateRequest payload, String length) {
        StringBuilder prompt = new StringBuilder();
        ArticleLengthSpec lengthSpec = articleLengthSpec(length);
        prompt.append("你是资深微信公众号主编和爆款选题策划，请为订阅号「早一步信息差」写一篇可直接发布的公众号正文。\n");
        prompt.append("公众号定位：信息差型热点解读。\n");
        prompt.append("公众号类目：科技 / 互联网、教育 / 职场、财政金融，也允许自定义栏目。\n");
        prompt.append("当前栏目：").append(category).append("\n");
        prompt.append("核心话题：").append(topic).append("\n");
        prompt.append("切入角度：").append(defaultText(payload.getAngle(), "先讲热点里的信息差，再落到普通人会受什么影响")).append("\n");
        prompt.append("目标读者：").append(defaultText(payload.getAudience(), "想快速看懂热点、不想被标题带节奏的读者")).append("\n");
        prompt.append("语气：").append(defaultText(payload.getTone(), "像朋友聊天，口语化，有判断但不端着，拒绝正式和学术腔")).append("\n");
        prompt.append("篇幅：").append(lengthSpec.getLabel()).append("，正文不少于 ").append(lengthSpec.getMinChars()).append(" 个中文字符，目标 ").append(lengthSpec.getTargetChars()).append(" 字左右。\n");
        prompt.append("风格参考：学习半佛仙人、差评、粥左罗等爆款号的拆解意识、故事感和标题敏感度；只学习方法论，不复制原文、金句、段落结构和口头禅。\n");
        prompt.append("原创底线：可以吸收公开资料里的事实和观点线索，但必须重新组织论证、重新表达，不得连续复用来源文章的句子；不要写成洗稿、搬运或近义词替换。\n");
        prompt.append("爆款方法论：开头要有强场景或反常识判断；中段要有冲突、利益关系、普通人处境和具体例子；每一节都要回答“这和读者有什么关系”；结尾要留下可讨论的问题。\n");
        prompt.append("表达要求：像一个有判断的朋友在讲，不要像 AI 总结；少用“首先/其次/最后”“值得注意的是”“综上所述”；多用短句、转折、具体场景和大白话解释。\n");
        prompt.append("交付标准：contentMarkdown 必须就是读者打开公众号后看到的最终正文，从文章标题开始，随后是完整导语、正文小标题、段落、金句或引用、结尾互动和参考资料。\n");
        prompt.append("严禁输出：选题方案、写作提纲、素材清单、资料整理、运营建议、创作说明、文章结构说明、提示词说明、可选标题列表。\n");
        prompt.append("不要写“下面是一篇文章”“本文将”“可以这样写”“建议从以下角度”等幕后话术。\n");
        prompt.append("文章结构：痛点/场景开头 -> 抛出核心矛盾 -> 4-6 个小标题展开 -> 给出有记忆点的金句/判断 -> 结尾收束到读者处境并邀请留言。\n");
        prompt.append("手机阅读要求：段落短，单段尽量 80 字以内；小标题有信息量；不要连续堆砌资料；不要正式、学术、研报腔。\n");
        prompt.append("事实要求：不得编造事实、数据、人物经历、政策和史料；资料不够时必须降级为观点分析，并在 riskTips 说明。\n");
        prompt.append("请严格输出 JSON，字段：title,digest,contentMarkdown,contentHtml,coverPrompt,tags,riskTips。\n");
        prompt.append("contentMarkdown 使用 #/## 标题、自然段、引用和列表；contentHtml 与 contentMarkdown 内容一致，只用 p/h1/h2/blockquote/ul/li/strong 标签，不要包含 script/style。tags/riskTips 是字符串数组。\n");
        return prompt.toString();
    }

    private ContentGenerationResult parseGenerationResult(String raw,
                                                          String topic,
                                                          String category,
                                                          ContentArticleGenerateRequest payload,
                                                          String length,
                                                          String model) {
        String text = trimToNull(raw);
        if (text == null) {
            return fallbackGeneration(topic, category, payload, length, model, defaultRiskTips());
        }
        Map<String, Object> json = tryReadMap(extractJsonObject(text));
        if (json == null) {
            ContentGenerationResult result = fallbackGeneration(topic, category, payload, length, model, defaultRiskTips());
            if (isPublishableArticleDraft(text, markdownToHtml(text), length)) {
                result.setMarkdown(ensureMarkdownTitle(text, result.getTitle()));
                result.setDigest(buildDigest(topic, payload.getAudience()));
                result.getRiskTips().add("AI 未返回标准 JSON，但正文达到可发布标准，已作为 Markdown 草稿保存。");
            } else {
                result.getRiskTips().add("AI 未返回标准 JSON 或正文未达标，已自动替换为完整公众号正文。");
            }
            return result;
        }
        String title = defaultText(asString(json.get("title")), topic);
        String digest = defaultText(asString(json.get("digest")), buildDigest(topic, payload.getAudience()));
        String markdown = firstText(json, "contentMarkdown", "markdown", "body", "article");
        markdown = ensureMarkdownTitle(defaultText(markdown, buildMarkdown(topic, payload, length)), title);
        String html = defaultText(firstText(json, "contentHtml", "html"), markdownToHtml(markdown));
        boolean publishable = isPublishableArticleDraft(markdown, html, length);
        String coverPrompt = defaultText(asString(json.get("coverPrompt")), buildCoverPrompt(topic, payload.getCoverStyle(), category));
        List<String> tags = readStringList(json.get("tags"));
        if (tags.isEmpty()) {
            tags = defaultTags(category);
        }
        List<String> risks = readStringList(json.get("riskTips"));
        if (risks.isEmpty()) {
            risks = defaultRiskTips();
        }
        if (!publishable) {
            ContentGenerationResult fallback = fallbackGeneration(topic, category, payload, length, model, risks);
            fallback.getRiskTips().add("AI 返回内容未达到可发布正文标准，已自动替换为完整公众号正文。");
            return fallback;
        }
        return new ContentGenerationResult(title, digest, markdown, coverPrompt, tags, risks, model);
    }

    private ContentGenerationResult fallbackGeneration(String topic,
                                                       String category,
                                                       ContentArticleGenerateRequest payload,
                                                       String length,
                                                       String model,
                                                       List<String> riskTips) {
        return new ContentGenerationResult(
                buildTitle(topic, category),
                buildDigest(topic, payload.getAudience()),
                buildMarkdown(topic, payload, length),
                buildCoverPrompt(topic, payload.getCoverStyle(), category),
                defaultTags(category),
                new ArrayList<>(riskTips),
                model
        );
    }

    private String generateCoverImageIfNeeded(String coverPrompt,
                                              String imageMode,
                                              Boolean generateCover,
                                              List<String> riskTips) {
        if (Boolean.FALSE.equals(generateCover) || "none".equalsIgnoreCase(defaultText(imageMode, DEFAULT_IMAGE_MODE))) {
            return null;
        }
        if (!hasText(config(ImageService.CFG_BASE_URL)) || !hasText(config(ImageService.CFG_API_KEY))) {
            riskTips.add("封面模型未配置，已跳过封面生成。");
            return null;
        }
        try {
            ImageGenerateRequest req = new ImageGenerateRequest();
            req.setPrompt(coverPrompt);
            req.setN(1);
            req.setSize("1024x1024");
            ImageGenerationsResponse response = imageService.generate(req);
            if (response.getData() != null) {
                for (ImageGenerationsResponse.ImageDataItem item : response.getData()) {
                    if (hasText(item.getUrl())) {
                        return item.getUrl();
                    }
                }
            }
            riskTips.add("封面模型没有返回可用图片 URL。");
        } catch (Exception e) {
            riskTips.add("封面生成失败：" + e.getMessage());
        }
        return null;
    }

    private String buildTitle(String topic, String category) {
        return topic.length() > 80 ? topic.substring(0, 80) : topic;
    }

    private String buildDigest(String topic, String audience) {
        String who = hasText(audience) ? audience.trim() : "想快速看懂热点的读者";
        return "围绕「" + topic + "」拆一拆背后的信息差，讲给" + who + "听。";
    }

    private String buildMarkdown(String topic, ContentArticleGenerateRequest payload, String length) {
        List<String> lines = new ArrayList<>();
        String angle = defaultText(payload.getAngle(), "这个话题表面是一个热点，背后其实藏着普通人容易忽略的信息差。");
        String audience = defaultText(payload.getAudience(), "想快速看懂热点、不想被标题带节奏的读者");
        String tone = defaultText(payload.getTone(), "像朋友聊天，口语化，有判断但不端着，拒绝正式和学术腔。");
        lines.add("# " + buildTitle(topic, DEFAULT_CATEGORY));
        lines.add("");
        lines.add("这两天，很多人都在聊「" + topic + "」。");
        lines.add("但我觉得，真正值得看的不是热闹本身，而是热闹背后那层信息差。");
        lines.add("因为很多时候，热点只是露在水面上的一小块冰，下面连着的是平台规则、行业变化、普通人的选择成本。");
        lines.add("");
        lines.add("> 真正有用的热点解读，不是帮你多看一条新闻，而是帮你少踩一个坑。");
        lines.add("");
        lines.add("## 一、先别急着站队，先看它为什么会火");
        lines.add("一个话题能被推到大家面前，通常不是因为它突然重要，而是因为它刚好撞上了很多人的共同感受。");
        lines.add("有人在里面看见机会，有人在里面看见风险，还有人只是觉得：这事好像和我没关系，但又隐隐有点不对劲。");
        lines.add("这就是信息差最容易出现的地方。");
        lines.add("表面上大家在讨论同一件事，实际上每个人看到的层次完全不同。有人只看到标题，有人看到规则变化，有人已经开始调整自己的选择。");
        lines.add("");
        lines.add("## 二、这件事真正的看点，不是发生了什么");
        lines.add(angle);
        lines.add("如果只是复述事件，那任何平台都能做。公众号真正要做的是把它讲成人话：这件事改变了什么？谁会受影响？普通人最容易误判哪一步？");
        lines.add("很多热点看起来离我们很远，但最后都会落到三个问题上：工作怎么变，钱往哪里流，人的选择会不会更贵。");
        lines.add("所以判断一个热点值不值得写，不是看它有没有流量，而是看它能不能解释读者正在经历的困惑。");
        lines.add("");
        lines.add("## 三、普通人最容易忽略的，是规则已经先变了");
        lines.add("很多人以为信息差就是“我比你早知道一个消息”。");
        lines.add("但更大的信息差，其实是别人已经理解了新规则，你还在用旧经验做判断。");
        lines.add("比如一个产品更新、一个平台动作、一条政策变化、一次公司调整，刚出来的时候都像新闻。");
        lines.add("可真正重要的是，它后面会不会带来连锁反应：行业怎么跟，用户怎么选，成本怎么分摊，机会又会转移到谁手里。");
        lines.add("如果只看第一层，就容易被情绪带跑。");
        lines.add("如果能多看两层，至少不会在下一步选择里太被动。");
        lines.add("");
        lines.add("## 四、别把热点写成焦虑，读者要的是判断");
        lines.add("「早一步信息差」要做的，不是制造一种“你再不懂就晚了”的恐吓感。");
        lines.add("真正好的内容，应该让读者读完以后更稳一点。");
        lines.add("他知道哪些地方需要继续观察，哪些结论现在还不能下，哪些变化可能真的和自己有关。");
        lines.add("这也是为什么口吻很重要。");
        lines.add(tone);
        lines.add("越是复杂的话题，越要把话说清楚。能用大白话讲明白，就别堆概念；能用具体场景说明，就别只喊观点。");
        lines.add("");
        lines.add("## 五、这篇稿子真正想提醒的是");
        lines.add("围绕「" + topic + "」，我更建议你记住三个判断。");
        lines.add("");
        lines.add("- 第一，热点只是入口，背后真正变化的是规则、成本和选择。");
        lines.add("- 第二，别只看谁声音大，要看谁的行动已经变了。");
        lines.add("- 第三，普通人不需要追每一个热点，但要学会识别和自己有关的信息差。");
        lines.add("");
        lines.add("对" + audience + "来说，最重要的不是把所有新闻都看完。");
        lines.add("而是当一个话题反复出现时，能多问一句：它背后的利益关系、规则变化和真实影响分别是什么？");
        lines.add("");
        lines.add("## 写在最后");
        lines.add("很多热点最后都会过去。");
        lines.add("但它留下的信号，往往会继续影响我们的工作、消费、学习和选择。");
        lines.add("所以别只把「" + topic + "」当成一个热闹。");
        lines.add("它更像一个提醒：变化发生时，最先被奖励的，通常不是最激动的人，而是最早看懂规则的人。");
        lines.add("");
        lines.add("如果你也关注这个话题，欢迎留言聊聊：你觉得这里面最容易被忽略的信息差是什么？");
        lines.add("");
        lines.add("## 参考资料");
        lines.add("- 热点来源与公开资料待发布前补充核验。");
        lines.add("- 涉及政策、财务、教育和平台规则的信息，请以官方发布和权威媒体报道为准。");
        return lines.stream().collect(Collectors.joining("\n"));
    }

    private String markdownToHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        boolean inList = false;
        for (String line : markdown.split("\\r?\\n")) {
            if (line.startsWith("# ")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<h1>").append(escape(line.substring(2))).append("</h1>");
            } else if (line.startsWith("## ")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<h2>").append(escape(line.substring(3))).append("</h2>");
            } else if (line.startsWith("> ")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<blockquote>").append(escape(line.substring(2))).append("</blockquote>");
            } else if (line.startsWith("- ")) {
                if (!inList) {
                    html.append("<ul>");
                    inList = true;
                }
                html.append("<li>").append(escape(line.substring(2))).append("</li>");
            } else if (line.trim().isEmpty()) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("\n");
            } else {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<p>").append(escape(line)).append("</p>");
            }
        }
        if (inList) {
            html.append("</ul>");
        }
        return html.toString();
    }

    private String ensureMarkdownTitle(String markdown, String title) {
        String trimmed = defaultText(markdown, "").trim();
        if (!hasText(trimmed)) {
            return "# " + defaultText(title, "早一步信息差");
        }
        if (trimmed.startsWith("# ")) {
            return trimmed;
        }
        return "# " + defaultText(title, "早一步信息差") + "\n\n" + trimmed;
    }

    private boolean isPublishableArticleDraft(String markdown, String html, String length) {
        String plain = htmlToPlainText(defaultText(html, markdownToHtml(defaultText(markdown, "")))).replaceAll("\\s+", "");
        if (plain.length() < articleLengthSpec(length).getMinChars()) {
            return false;
        }
        String normalized = defaultText(markdown, "") + "\n" + htmlToPlainText(defaultText(html, ""));
        List<String> forbidden = Arrays.asList(
                "选题方案",
                "写作提纲",
                "素材清单",
                "资料整理",
                "运营建议",
                "创作说明",
                "文章结构说明",
                "提示词",
                "可选标题",
                "下面是一篇",
                "下面这篇",
                "可以这样写",
                "建议从以下角度",
                "本文将从",
                "原文如下",
                "改写如下",
                "仿写",
                "洗稿",
                "伪原创",
                "规避检测"
        );
        for (String pattern : forbidden) {
            if (normalized.contains(pattern)) {
                return false;
            }
        }
        int h2Count = countMatches(defaultText(markdown, ""), "## ") + countMatches(defaultText(html, "").toLowerCase(), "<h2");
        int paragraphCount = countMatches(defaultText(html, "").toLowerCase(), "<p");
        if (paragraphCount == 0) {
            paragraphCount = defaultText(markdown, "").split("\\n\\s*\\n").length;
        }
        return h2Count >= 3 && paragraphCount >= 8;
    }

    private ArticleLengthSpec articleLengthSpec(String length) {
        String value = defaultText(length, "standard").toLowerCase();
        if ("short".equals(value)) {
            return new ArticleLengthSpec("短稿", 700, 1000);
        }
        if ("long".equals(value)) {
            return new ArticleLengthSpec("长稿", 1800, 2600);
        }
        return new ArticleLengthSpec("标准稿", 1200, 1600);
    }

    private String htmlToPlainText(String html) {
        return defaultText(html, "").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private int countMatches(String value, String needle) {
        if (!hasText(value) || !hasText(needle)) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private String buildCoverPrompt(String topic, String style, String category) {
        return "公众号封面图，主题：" + topic + "，栏目：" + category + "，风格：" + defaultText(style, "干净、清晰、有传播感");
    }

    private List<HotSource> hotSources(String category) {
        String configured = defaultText(config("content.hot.sources." + category), config("content.hot.sources"));
        if (!hasText(configured)) {
            return defaultHotSources(category);
        }
        List<HotSource> parsed = parseHotSourcesJson(configured);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        parsed = parseHotSourcesLines(configured);
        return parsed.isEmpty() ? defaultHotSources(category) : parsed;
    }

    private List<HotSource> defaultHotSources(String category) {
        String normalized = defaultText(category, "");
        List<HotSource> sources = new ArrayList<>();
        if (normalized.contains("教育") || normalized.contains("职场")) {
            sources.add(nowhotsSource("nowhots-zhihu", "即时热点 · 知乎", "zhihu"));
            sources.add(nowhotsSource("nowhots-weibo", "即时热点 · 微博", "weibo"));
        } else if (normalized.contains("财政") || normalized.contains("金融") || normalized.contains("财经")) {
            sources.add(nowhotsSource("nowhots-36kr", "即时热点 · 36氪", "36kr"));
            sources.add(nowhotsSource("nowhots-zhihu", "即时热点 · 知乎", "zhihu"));
        } else {
            sources.add(nowhotsSource("nowhots-ithome", "即时热点 · IT之家", "ithome"));
            sources.add(nowhotsSource("nowhots-36kr", "即时热点 · 36氪", "36kr"));
            sources.add(nowhotsSource("nowhots-zhihu", "即时热点 · 知乎", "zhihu"));
        }
        sources.add(new HotSource("baidu", "百度热榜", "https://top.baidu.com/api/board?platform=wise&tab=realtime"));
        sources.add(new HotSource("toutiao", "头条热榜", "https://www.toutiao.com/hot-event/hot-board/?origin=toutiao_pc"));
        return sources;
    }

    private HotSource nowhotsSource(String id, String name, String code) {
        return new HotSource(id, name, NOWHOTS_API_BASE + code);
    }

    private List<HotSource> parseHotSourcesJson(String configured) {
        Object value = readObject(configured);
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<?> rows = (List<?>) value;
        List<HotSource> sources = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (!(row instanceof Map)) {
                continue;
            }
            Map<?, ?> record = (Map<?, ?>) row;
            String url = firstString(record, "url", "href", "api");
            if (!hasText(url)) {
                continue;
            }
            String id = defaultText(firstString(record, "id", "source"), "source-" + (i + 1));
            String name = defaultText(firstString(record, "name", "label", "title"), id);
            sources.add(new HotSource(id, name, url));
        }
        return sources;
    }

    private List<HotSource> parseHotSourcesLines(String configured) {
        List<HotSource> sources = new ArrayList<>();
        String[] lines = configured.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = trimToNull(lines[i]);
            if (line == null) {
                continue;
            }
            String[] parts = line.split("\\|", 2);
            if (parts.length < 2 || !hasText(parts[1])) {
                continue;
            }
            String name = defaultText(parts[0], "Source " + (i + 1));
            sources.add(new HotSource("custom-" + (i + 1), name, parts[1].trim()));
        }
        return sources;
    }

    private List<Map<String, Object>> fetchHotSource(HotSource source, int limit) throws IOException {
        Request request = new Request.Builder()
                .url(source.getUrl())
                .header("Accept", "application/json")
                .header("User-Agent", "website-content-factory/1.0")
                .get()
                .build();
        OkHttpClient client = okHttpClient.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .writeTimeout(4, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return Collections.emptyList();
            }
            Object payload = objectMapper.readValue(response.body().string(), Object.class);
            List<?> rows = extractHotRows(payload);
            List<Map<String, Object>> topics = new ArrayList<>();
            int max = Math.min(limit, rows.size());
            for (int i = 0; i < max; i++) {
                Map<String, Object> topic = hotTopicView(rows.get(i), source, i + 1);
                if (topic != null) {
                    topics.add(topic);
                }
            }
            return topics;
        }
    }

    private List<?> extractHotRows(Object payload) {
        return extractHotRows(payload, 0);
    }

    private List<?> extractHotRows(Object payload, int depth) {
        if (payload == null || depth > 6) {
            return Collections.emptyList();
        }
        if (payload instanceof List) {
            List<?> list = (List<?>) payload;
            if (looksLikeHotRows(list)) {
                return list;
            }
            for (Object item : list) {
                List<?> nested = extractHotRows(item, depth + 1);
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
            return Collections.emptyList();
        }
        if (!(payload instanceof Map)) {
            return Collections.emptyList();
        }
        Map<?, ?> record = (Map<?, ?>) payload;
        for (String key : Arrays.asList("data", "items", "list", "rows", "result", "hotList", "news", "cards", "content")) {
            Object value = record.get(key);
            List<?> nested = extractHotRows(value, depth + 1);
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        for (Object value : record.values()) {
            List<?> nested = extractHotRows(value, depth + 1);
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        return Collections.emptyList();
    }

    private boolean looksLikeHotRows(List<?> rows) {
        for (Object row : rows) {
            if (row instanceof Map && hasText(firstString((Map<?, ?>) row,
                    "title", "Title", "name", "Name", "word", "Word", "keyword", "query", "desc", "display_query"))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> hotTopicView(Object row, HotSource source, int rank) {
        if (!(row instanceof Map)) {
            return null;
        }
        Map<?, ?> record = (Map<?, ?>) row;
        String title = firstString(record, "title", "Title", "name", "Name", "word", "Word", "keyword", "query", "desc", "display_query");
        if (!hasText(title)) {
            return null;
        }
        Map<String, Object> topic = new LinkedHashMap<>();
        topic.put("id", source.getId() + ":" + rank + ":" + hashText(title).substring(0, 8));
        topic.put("source", source.getId());
        topic.put("sourceName", source.getName());
        topic.put("rank", parseRank(record.get("rank"), record.get("index"), rank));
        topic.put("title", title);
        topic.put("url", trimToNull(firstString(record, "url", "Url", "link", "mobileUrl", "pcUrl", "href")));
        topic.put("hot", trimToNull(firstString(record, "hot", "heat", "score", "hotValue", "HotValue", "views", "metrics", "Label")));
        topic.put("summary", trimToNull(firstString(record, "summary", "excerpt", "description", "desc", "Abstract")));
        topic.put("capturedAt", LocalDateTime.now().toString());
        return topic;
    }

    private int parseRank(Object rank, Object index, int fallback) {
        for (Object value : Arrays.asList(rank, index)) {
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            if (value != null) {
                try {
                    return Integer.parseInt(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    // Try the next value.
                }
            }
        }
        return fallback;
    }

    private List<Map<String, Object>> rankHotTopics(List<Map<String, Object>> topics, String category, int limit) {
        List<Map<String, Object>> ranked = new ArrayList<>(topics);
        ranked.sort(Comparator
                .comparingInt((Map<String, Object> item) -> topicCategoryScore(item, category)).reversed()
                .thenComparingInt(item -> parseRank(item.get("rank"), null, 999)));
        return ranked.stream().limit(limit).collect(Collectors.toList());
    }

    private int topicCategoryScore(Map<String, Object> topic, String category) {
        String text = (defaultText(asString(topic.get("title")), "") + " "
                + defaultText(asString(topic.get("summary")), "") + " "
                + defaultText(asString(topic.get("hot")), "")).toLowerCase();
        int score = 0;
        for (String keyword : hotKeywords(category)) {
            if (text.contains(keyword.toLowerCase())) {
                score += 6;
            }
        }
        return score;
    }

    private List<String> hotKeywords(String category) {
        String value = category == null ? "" : category;
        if (value.contains("教育") || value.contains("职场")) {
            return Arrays.asList("教育", "职场", "就业", "考研", "高考", "大学", "培训", "简历", "招聘", "裁员", "转行", "职业");
        }
        if (value.contains("财政") || value.contains("金融") || value.contains("财经")) {
            return Arrays.asList("财经", "金融", "股市", "基金", "银行", "楼市", "房贷", "消费", "经济", "政策", "公司", "财报");
        }
        return Arrays.asList("科技", "互联网", "AI", "人工智能", "大模型", "手机", "芯片", "平台", "产品", "公司", "电商", "应用");
    }

    private List<Map<String, Object>> fallbackHotTopics(String category) {
        String normalized = category == null ? "" : category;
        List<String> titles;
        if (normalized.contains("教育") || normalized.contains("职场")) {
            titles = Arrays.asList(
                    "今天职场和教育里的新信息差，普通人该先看懂什么",
                    "年轻人选择工作和学习路径时，最容易忽略的成本",
                    "招聘、培训和转行热度背后，真正变化的是什么"
            );
        } else if (normalized.contains("财政") || normalized.contains("金融") || normalized.contains("财经")) {
            titles = Arrays.asList(
                    "今天财经热点背后的信息差，和普通人的钱包有什么关系",
                    "消费、楼市和市场情绪变化里，普通人该看哪几个信号",
                    "公司新闻和政策变化背后，哪些影响会传导到日常生活"
            );
        } else {
            titles = Arrays.asList(
                    "今天科技互联网热点背后的信息差，普通人别只看标题",
                    "AI 产品和平台变化背后，哪些机会和坑最容易被忽略",
                    "大厂动作、产品更新和行业变化里，真正值得写的是什么"
            );
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            Map<String, Object> topic = new LinkedHashMap<>();
            topic.put("id", "fallback:" + hashText(category + ":" + titles.get(i)).substring(0, 10));
            topic.put("source", "fallback");
            topic.put("sourceName", "本地兜底选题");
            topic.put("rank", i + 1);
            topic.put("title", titles.get(i));
            topic.put("url", null);
            topic.put("hot", "兜底选题");
            topic.put("summary", "公共热榜源暂时不可用时，用于保证内容工厂仍可继续选题和生成草稿。");
            topic.put("capturedAt", LocalDateTime.now().toString());
            result.add(topic);
        }
        return result;
    }

    private String firstString(Map<?, ?> record, String... keys) {
        for (String key : keys) {
            Object value = record.get(key);
            if (value == null) {
                continue;
            }
            String text = trimToNull(String.valueOf(value));
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private String firstText(Map<String, Object> record, String... keys) {
        for (String key : keys) {
            String value = trimToNull(asString(record.get(key)));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String hashText(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(defaultText(value, "").getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes(defaultText(value, "").getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        }
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

    private boolean freePublishEnabled() {
        return "true".equalsIgnoreCase(config("wechat.freePublishEnabled"));
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

    private Map<String, Object> tryReadMap(String json) {
        if (!hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonObject(String value) {
        String text = value.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private List<Object> asList(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        return Collections.emptyList();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<String> readStringList(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<?> raw = (List<?>) value;
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            String text = trimToNull(item == null ? null : String.valueOf(item));
            if (text != null) {
                result.add(text);
            }
        }
        return result;
    }

    private String defaultText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private String normalizeCategory(String value) {
        String category = defaultText(value, DEFAULT_CATEGORY);
        return category.length() > 80 ? category.substring(0, 80) : category;
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

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class ContentGenerationResult {
        private String title;
        private String digest;
        private String markdown;
        private String coverPrompt;
        private List<String> tags;
        private List<String> riskTips;
        private String model;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class ArticleLengthSpec {
        private String label;
        private int minChars;
        private int targetChars;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class HotSource {
        private String id;
        private String name;
        private String url;
    }
}
