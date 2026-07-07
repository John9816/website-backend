package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.ImageGenerateRequest;
import com.example.website.dto.ImageGenerationsResponse;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private static final String DEFAULT_CATEGORY = "科技 / 互联网";
    private static final String DEFAULT_LAYOUT = "clean";
    private static final String DEFAULT_IMAGE_MODE = "generate";
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
            AiChatUpstreamClient.ChatCompletionResult result = aiChatUpstreamClient.completeQuick(
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
        prompt.append("请为订阅号「早一步信息差」生成一篇可编辑的公众号草稿。\n");
        prompt.append("公众号定位：信息差型热点解读。\n");
        prompt.append("公众号类目：科技 / 互联网、教育 / 职场、财政金融，也允许自定义栏目。\n");
        prompt.append("当前栏目：").append(category).append("\n");
        prompt.append("核心话题：").append(topic).append("\n");
        prompt.append("切入角度：").append(defaultText(payload.getAngle(), "先讲热点里的信息差，再落到普通人会受什么影响")).append("\n");
        prompt.append("目标读者：").append(defaultText(payload.getAudience(), "想快速看懂热点、不想被标题带节奏的读者")).append("\n");
        prompt.append("语气：").append(defaultText(payload.getTone(), "像朋友聊天，口语化，有判断但不端着，拒绝正式和学术腔")).append("\n");
        prompt.append("篇幅：").append(length).append("。short 约 900-1200 字，standard 约 1400-1800 字，long 约 2200-3000 字。\n");
        prompt.append("风格参考：学习半佛仙人、差评、粥左罗等爆款号的拆解意识、故事感和标题敏感度，但不要模仿口头禅，不要洗稿。\n");
        prompt.append("要求：开头 3 句话内讲清楚热点是什么；中间拆 3-5 个信息差；每段短一点，口语化；避免正式、学术、研报腔；不要编造事实来源；不确定的地方写成待核实。\n");
        prompt.append("请严格输出 JSON，字段：title,digest,markdown,tags,riskTips,coverPrompt。\n");
        prompt.append("markdown 使用 #/## 标题和自然段，不要输出 HTML。tags/riskTips 是字符串数组。\n");
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
            result.setMarkdown(text);
            result.setDigest(buildDigest(topic, payload.getAudience()));
            result.getRiskTips().add("AI 未返回标准 JSON，已把原文作为 Markdown 草稿保存。");
            return result;
        }
        String title = defaultText(asString(json.get("title")), topic);
        String digest = defaultText(asString(json.get("digest")), buildDigest(topic, payload.getAudience()));
        String markdown = defaultText(asString(json.get("markdown")), buildMarkdown(topic, payload, length));
        String coverPrompt = defaultText(asString(json.get("coverPrompt")), buildCoverPrompt(topic, payload.getCoverStyle(), category));
        List<String> tags = readStringList(json.get("tags"));
        if (tags.isEmpty()) {
            tags = defaultTags(category);
        }
        List<String> risks = readStringList(json.get("riskTips"));
        if (risks.isEmpty()) {
            risks = defaultRiskTips();
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
        lines.add("# " + topic);
        lines.add("");
        lines.add("## 这事先说结论");
        lines.add(defaultText(payload.getAngle(), "这个选题值得写，因为它表面是一个热点，背后其实藏着普通人容易忽略的信息差。"));
        lines.add("");
        lines.add("## 信息差在哪");
        lines.add("别急着只看热搜标题。真正值得拆的是：谁从这件事里受影响，谁在改变规则，普通人如果不知道，会在哪一步吃亏。");
        lines.add("");
        lines.add("## 可以怎么写");
        lines.add("先用一句大白话把事件讲清楚，再拆 2-3 个容易被忽略的点，最后落到读者能做什么、该避开什么坑。");
        lines.add("");
        lines.add("## 口吻要求");
        lines.add(defaultText(payload.getTone(), "像朋友聊天，口语化，有判断但不端着，拒绝正式和学术腔。"));
        lines.add("");
        lines.add("## 发布前");
        lines.add("请补充事实来源、案例、配图和最终标题。篇幅偏好：" + length + "。订阅号无 freepublish 权限，请入草稿后到公众号后台人工发布。");
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
}
