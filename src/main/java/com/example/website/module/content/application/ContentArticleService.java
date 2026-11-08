package com.example.website.module.content.application;

import com.example.website.common.BusinessException;
import com.example.website.shared.dto.PageView;
import com.example.website.module.content.api.dto.ContentAgentRunRequest;
import com.example.website.module.content.api.dto.ContentAgentRunResult;
import com.example.website.module.content.api.dto.ContentArticleGenerateRequest;
import com.example.website.module.content.api.dto.ContentArticleUpdateRequest;
import com.example.website.module.content.api.dto.ContentArticleView;
import com.example.website.module.content.api.dto.ContentAutomationView;
import com.example.website.module.content.api.dto.ContentFactoryStatusView;
import com.example.website.module.content.api.dto.ContentHotTopicsView;
import com.example.website.module.content.api.dto.ContentStatusConfigView;
import com.example.website.module.content.domain.ContentArticle;
import com.example.website.module.content.infrastructure.persistence.ContentArticleRepository;
import com.example.website.module.ai.application.AiChatUpstreamClient;
import com.example.website.module.images.application.GeneratedImageService;
import com.example.website.module.images.application.ImageService;
import com.example.website.module.settings.application.SysConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.example.website.module.content.application.ContentTextUtils.asList;
import static com.example.website.module.content.application.ContentTextUtils.asString;
import static com.example.website.module.content.application.ContentTextUtils.defaultText;
import static com.example.website.module.content.application.ContentTextUtils.extractJsonObject;
import static com.example.website.module.content.application.ContentTextUtils.hasText;
import static com.example.website.module.content.application.ContentTextUtils.htmlToPlainText;
import static com.example.website.module.content.application.ContentTextUtils.readStringList;
import static com.example.website.module.content.application.ContentTextUtils.trimToNull;
import static com.example.website.module.content.application.MarkdownHtmlConverter.markdownToHtml;

import static com.example.website.module.content.application.ArticleDraftText.*;

@Service
@RequiredArgsConstructor
public class ContentArticleService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_CATEGORY = "科技 / 互联网";
    private static final String DEFAULT_LAYOUT = "clean";
    private static final String DEFAULT_IMAGE_MODE = "generate";
    /** Optional progress callbacks for async job stage labels. */
    public interface GenerateProgress {
        void stage(String stage, String label);
    }

    private static final GenerateProgress NOOP_PROGRESS = new GenerateProgress() {
        @Override
        public void stage(String stage, String label) {
            // no-op
        }
    };

    private final ContentArticleRepository articleRepository;
    private final SysConfigService configService;
    private final ObjectMapper objectMapper;
    private final WechatOfficialAccountClient wechatClient;
    private final AiChatUpstreamClient aiChatUpstreamClient;
    private final ContentAutomationBuilder automationBuilder;
    private final HotTopicService hotTopicService;
    private final ContentPipelineService pipelineService;
    private final EditorialDecisionService editorialDecisionService;
    private final TopicCandidateService topicCandidateService;
    private final ArticleVisualService articleVisualService;
    private final GeneratedImageService generatedImageService;
    private final ArticleDraftWriter draftWriter;

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
        configs.add(new ContentStatusConfigView("image.api.key", "封面图片密钥", usableImageKey(imageKey)));
        configs.add(new ContentStatusConfigView("wechat.appId", "公众号 AppID", hasText(appId)));
        configs.add(new ContentStatusConfigView("wechat.appSecret", "公众号 AppSecret", hasText(appSecret)));
        configs.add(new ContentStatusConfigView("wechat.coverMediaId", "默认微信封面素材", hasText(coverMediaId)));
        configs.add(new ContentStatusConfigView("wechat.freePublishEnabled", "微信一键发布权限", "true".equalsIgnoreCase(freePublishEnabled)));

        boolean autopilotOn = "true".equalsIgnoreCase(config("content.autopilot.enabled"))
                && hasText(config("content.autopilot.userId"));
        configs.add(new ContentStatusConfigView("content.autopilot.enabled", "自动流水线运行中", autopilotOn));
        configs.add(new ContentStatusConfigView("content.autopilot.autoPublish", "自动群发（需服务号）",
                "true".equalsIgnoreCase(config("content.autopilot.autoPublish"))));

        return new ContentFactoryStatusView(
                hasText(aiBase) && hasText(aiKey),
                hasText(imageBase) && usableImageKey(imageKey),
                hasText(appId) && hasText(appSecret),
                configs
        );
    }

    public ContentHotTopicsView hotTopics(int limit, String category) {
        return hotTopicService.hotTopics(limit, normalizeCategory(category));
    }

    @Transactional
    public ContentAgentRunResult runAgent(Long userId, ContentAgentRunRequest req) {
        return runAgent(userId, req, NOOP_PROGRESS);
    }

    @Transactional
    public ContentAgentRunResult runAgent(Long userId, ContentAgentRunRequest req, GenerateProgress progress) {
        GenerateProgress onProgress = progress == null ? NOOP_PROGRESS : progress;
        ContentAgentRunRequest payload = req == null ? new ContentAgentRunRequest() : req;
        String category = normalizeCategory(payload.getCategory());
        onProgress.stage("topic", "自动选题中…");
        Map<String, Object> selectedTopic = selectAgentTopic(userId, payload, category);

        ContentArticleGenerateRequest generateRequest = new ContentArticleGenerateRequest();
        generateRequest.setTopic(asString(selectedTopic.get("title")));
        generateRequest.setCategory(category);
        generateRequest.setAngle(defaultText(asString(selectedTopic.get("angle")), defaultAgentAngle(category)));
        generateRequest.setAudience(defaultText(asString(selectedTopic.get("audience")), "想快速看懂热点、不想被标题带节奏的读者"));
        generateRequest.setTone("像朋友聊天，口语化，有判断但不端着，拒绝正式、学术和研报腔");
        generateRequest.setLength(defaultText(payload.getLength(), "standard"));
        generateRequest.setLayoutTheme(DEFAULT_LAYOUT);
        // Hot-topic → WeChat path: AI 生图 for cover + inline (product requirement).
        // Callers can still pass generateCover=false to skip all image API spend.
        boolean wantCover = !Boolean.FALSE.equals(payload.getGenerateCover());
        generateRequest.setImageMode(wantCover ? DEFAULT_IMAGE_MODE : "none"); // DEFAULT = generate
        generateRequest.setResearchEnabled(true);
        generateRequest.setResearchDepth("standard");
        generateRequest.setGenerateCover(wantCover);
        // Product default: after a publishable draft, push WeChat 草稿箱 (not freepublish).
        generateRequest.setAutoWechatDraft(!Boolean.FALSE.equals(payload.getAutoWechatDraft()));
        generateRequest.setAutoPublish(Boolean.TRUE.equals(payload.getAutoPublish()));
        generateRequest.setTopics(Collections.singletonList(selectedTopic));
        generateRequest.setCoverStyle("热点信息卡片，清晰醒目，适合订阅号首图");

        ContentArticleView generated = generate(userId, generateRequest, onProgress);
        ContentArticle article = requireArticle(userId, generated.getId());
        if (pipelineService.blocksWechatDraft(article)) {
            article = markQualityGateBlocked(article);
        } else if (!Boolean.FALSE.equals(payload.getAutoWechatDraft())) {
            article = createWechatDraftArticle(article);
        }
        // Auto group-send is only attempted when explicitly requested and the account holds the
        // freepublish permission; otherwise the article stays a draft for manual sending. A publish
        // failure is swallowed so the (already created) draft is still returned to the caller.
        if (Boolean.TRUE.equals(payload.getAutoPublish()) && freePublishEnabled()
                && !pipelineService.blocksWechatDraft(article)) {
            try {
                article = publishWechatArticle(article);
            } catch (BusinessException ignored) {
                article = articleRepository.findById(article.getId()).orElse(article);
            }
        }
        ContentAutomationView automation = automationBuilder.buildAgentAutomation(article, selectedTopic);
        article.setAutomationJson(writeJson(automation));
        article = articleRepository.save(article);

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("mediaId", article.getWechatMediaId());
        draft.put("url", article.getWechatUrl());
        draft.put("mode", isLocalDraft(article) ? "local" : "wechat");
        return new ContentAgentRunResult(view(article), automation, selectedTopic, draft);
    }

    public PageView<ContentArticleView> list(Long userId, int page, int size) {
        Page<ContentArticleRepository.ContentArticleSummary> result = articleRepository.findSummariesByUserId(
                userId,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE))
        );
        return PageView.from(result, this::summaryView);
    }

    /**
     * Site-wide article listing for the admin content console.  This is kept
     * separate from {@link #list(Long, int, int)} so ordinary user callers
     * always retain the owner predicate.
     */
    public PageView<ContentArticleView> listForAdmin(int page, int size) {
        Page<ContentArticleRepository.ContentArticleSummary> result = articleRepository.findAllSummariesForAdmin(
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE))
        );
        return PageView.from(result, this::summaryView);
    }

    public ContentArticleView get(Long userId, Long id) {
        return view(requireArticle(userId, id));
    }

    /** Read any article from the site-wide admin console. */
    public ContentArticleView getForAdmin(Long id) {
        return view(requireArticleForAdmin(id));
    }

    @Transactional
    public ContentArticleView generate(Long userId, ContentArticleGenerateRequest req) {
        return generate(userId, req, NOOP_PROGRESS);
    }

    @Transactional
    public ContentArticleView generate(Long userId, ContentArticleGenerateRequest req, GenerateProgress progress) {
        GenerateProgress onProgress = progress == null ? NOOP_PROGRESS : progress;
        ContentArticleGenerateRequest payload = req == null ? new ContentArticleGenerateRequest() : req;
        String topic = firstTopicTitle(payload);
        String category = normalizeCategory(payload.getCategory());
        String layoutTheme = defaultText(payload.getLayoutTheme(), DEFAULT_LAYOUT);
        String imageMode = defaultText(payload.getImageMode(), DEFAULT_IMAGE_MODE);
        String length = defaultText(payload.getLength(), "standard");

        // TrendPublish quality pipeline, pre-writing: 证据补全 (evidence) + 文章计划 (plan). The
        // returned prompt blocks steer the writer; the artifacts and risk notes are persisted below.
        boolean researchEnabled = !Boolean.FALSE.equals(payload.getResearchEnabled());
        onProgress.stage("research", researchEnabled ? "在找公开线索…" : "已跳过联网检索…");
        ContentPipelineService.PreWriting prep = pipelineService.prepare(
                topic, category, payload.getAngle(), payload.getAudience(), researchEnabled);
        List<Map<String, Object>> evidenceSources = prep.getEvidenceSources();
        if ((evidenceSources == null || evidenceSources.isEmpty()) && payload.getTopics() != null) {
            evidenceSources = fallbackEvidenceSources(payload.getTopics(), topic);
        }
        // Offline / no-research runs: keep a structural evidence stub so the text gate does not
        // permanently sit 6 points under the bar for an intentional product choice.
        if ((evidenceSources == null || evidenceSources.isEmpty()) && !researchEnabled) {
            Map<String, Object> stub = new LinkedHashMap<>();
            stub.put("title", "未联网检索（编辑核验边界）");
            stub.put("url", "");
            stub.put("snippet", "本篇关闭了公开网页检索；事实、数字与平台规则需发布前人工核验，不可当作已核实信源。");
            stub.put("source", "offline");
            evidenceSources = new ArrayList<>();
            evidenceSources.add(stub);
        }
        String evidenceBlock = prep.getEvidenceBlock();
        if (!hasText(evidenceBlock) && evidenceSources != null && !evidenceSources.isEmpty()) {
            evidenceBlock = evidenceSourcesToPromptBlock(evidenceSources);
        }
        if (!hasText(evidenceBlock) && !researchEnabled) {
            evidenceBlock = "（已关闭联网检索）事实边界：禁止编造精确数据与政策条文；无把握就写成分析判断，正文不要写参考资料小节。";
        }
        onProgress.stage("plan", "在定核心判断与小节…");
        // plan already produced inside prepare; stage is for UX pacing
        onProgress.stage("write", "在写正文…");
        ContentGenerationResult generated = draftWriter.generateArticleContent(
                topic, category, payload, length, evidenceBlock, prep.getPlanBlock());

        // Post-writing: editorial review + directed revision(s) against the publish bar.
        onProgress.stage("review", "在审稿与定向改写…");
        ContentPipelineService.Reviewed reviewed = pipelineService.finalize(
                topic,
                category,
                generated.getMarkdown(),
                length,
                evidenceSources != null && !evidenceSources.isEmpty(),
                prep.planAsMap() != null,
                generated.isFallbackUsed(),
                evidenceBlock,
                prep.getPlanBlock());
        String finalMarkdown = ensurePublishReadyLead(
                ensurePublishReadyTail(reviewed.getMarkdown(), evidenceSources), topic);
        // Open-rate first: sharpen title for curiosity/emotion, keep H1 + article.title in sync.
        String clickTitle = sharpenClickTitle(
                extractTitleFromMarkdown(finalMarkdown, generated.getTitle()), topic);
        finalMarkdown = ensureMarkdownTitle(finalMarkdown, clickTitle);
        String clickDigest = sharpenDigest(generated.getDigest(), topic, clickTitle);

        List<String> riskTips = new ArrayList<>(generated.getRiskTips());
        riskTips.addAll(prep.getRiskTips());
        riskTips.addAll(reviewed.getRiskTips());

        String coverPrompt = defaultText(generated.getCoverPrompt(), buildCoverPrompt(topic, payload.getCoverStyle(), category));
        String coverImageUrl = null;
        List<ArticleInlineImage> inlineImages;
        // imageMode=none / generateCover=false: never call image APIs (save WisArt / open-image quota).
        boolean skipVisuals = "none".equalsIgnoreCase(imageMode) || Boolean.FALSE.equals(payload.getGenerateCover());
        if (skipVisuals) {
            onProgress.stage("visual", "已跳过配图（不消耗生图额度）…");
            imageMode = "none";
            inlineImages = new ArrayList<>();
            riskTips.add("已跳过封面与内联配图：本次请求未调用生图接口。推微信草稿前请补图或改 imageMode。");
        } else {
            onProgress.stage("visual", "在配封面和插图…");
            coverImageUrl = generateCoverImageIfNeeded(coverPrompt, imageMode, payload.getGenerateCover(), riskTips);
            inlineImages = articleVisualService.create(topic, finalMarkdown, imageMode);
            long readyInlineImages = inlineImages.stream().filter(ArticleInlineImage::ready).count();
            if (readyInlineImages < 2) {
                riskTips.add("内联配图不足：需要 2-3 张可用图，当前就绪 " + readyInlineImages + " 张。");
            }
        }
        String contentHtml = renderArticleHtml(finalMarkdown, inlineImages, layoutTheme, category);
        // Skip render/visual re-score when images were intentionally omitted (avoids false 78/block).
        if (!skipVisuals) {
            ContentPipelineService.Reviewed renderedReview = pipelineService.refineAfterRender(
                    reviewed, topic, contentHtml, inlineImages);
            if (renderedReview != null) {
                reviewed = renderedReview;
                riskTips.addAll(renderedReview.getRiskTips() == null
                        ? Collections.emptyList()
                        : renderedReview.getRiskTips().stream()
                        .filter(item -> !riskTips.contains(item))
                        .collect(Collectors.toList()));
            }
        }

        onProgress.stage("gate", "在做发布就绪检查…");
        ContentArticle article = new ContentArticle();
        article.setUserId(userId);
        article.setTitle(clickTitle);
        article.setDigest(clickDigest);
        article.setContentMarkdown(finalMarkdown);
        article.setContentHtml(contentHtml);
        article.setCoverPrompt(coverPrompt);
        article.setCoverImageUrl(coverImageUrl);
        article.setInlineImagesJson(writeJson(inlineImages));
        article.setTopicsJson(writeJson(defaultTopics(payload, topic)));
        article.setTagsJson(writeJson(generated.getTags().isEmpty() ? defaultTags(category) : generated.getTags()));
        article.setRiskTipsJson(writeJson(riskTips));
        article.setModel(generated.getModel());
        article.setCategory(category);
        article.setLayoutTheme(layoutTheme);
        article.setImageMode(imageMode);
        article.setAutomationJson(writeJson(ContentAutomationView.empty()));
        article.setPlanJson(prep.planAsMap() == null ? null : writeJson(prep.planAsMap()));
        article.setEvidenceJson(evidenceSources == null || evidenceSources.isEmpty()
                ? null : writeJson(evidenceSources));
        article.setReviewJson(reviewed.reviewAsMap() == null ? null : writeJson(reviewed.reviewAsMap()));
        // imageMode=none: score text readiness only (do not tank score for intentional no-image runs).
        // WeChat draft still re-checks full evaluateArticle (cover + 2-3 inline images).
        PublishableArticleContract.Evaluation deterministicGate = pipelineService.evaluateGate(
                article, length, imageMode, generated.isFallbackUsed());
        // AI review is advisory for rewrite loops; the deterministic contract (after lead/tail
        // ensure + visuals) decides WeChat readiness. Never let a soft AI 86 permanently block
        // a draft whose full contract already clears 88 with cover/inline ready.
        int reviewScore = reviewed.qualityScore() == null ? 0 : reviewed.qualityScore();
        int contractScore = deterministicGate.getScore();
        article.setQualityScore(pipelineService.reconcileQualityScore(reviewScore, contractScore, deterministicGate));
        article.setStatus(ContentArticle.STATUS_DRAFT);
        pipelineService.applyQualityState(article, deterministicGate, generated.isFallbackUsed());

        ContentArticle saved = articleRepository.save(article);
        try {
            if (pipelineService.blocksWechatDraft(saved)) {
                saved = markQualityGateBlocked(saved);
            } else if (Boolean.TRUE.equals(payload.getAutoWechatDraft()) || Boolean.TRUE.equals(payload.getAutoPublish())) {
                saved = createWechatDraftArticle(saved);
            }
            if (Boolean.TRUE.equals(payload.getAutoPublish()) && !pipelineService.blocksWechatDraft(saved)) {
                saved = publishWechatArticle(saved);
            }
        } catch (BusinessException ignored) {
            saved = articleRepository.findById(saved.getId()).orElse(saved);
        }
        onProgress.stage("done", saved.getErrorMessage() == null ? "草稿已就绪" : "草稿已生成，待改稿后推微信");
        return view(saved);
    }

    /**
     * Directed rewrite of an existing draft using the review/revision loop (does not re-pick topic).
     */
    @Transactional
    public ContentArticleView revise(Long userId, Long id) {
        return reviseArticle(requireArticle(userId, id));
    }

    /** Directed rewrite of any draft from the site-wide admin console. */
    @Transactional
    public ContentArticleView reviseForAdmin(Long id) {
        return reviseArticle(requireArticleForAdminUpdate(id));
    }

    private ContentArticleView reviseArticle(ContentArticle article) {
        prepareEditable(article);
        String markdown = trimToNull(article.getContentMarkdown());
        if (markdown == null) {
            throw new BusinessException(400, "当前草稿没有 Markdown 正文，无法定向改写");
        }
        String topic = resolveArticleTopic(article);
        String category = normalizeCategory(article.getCategory());
        String evidenceBlock = evidenceJsonToPromptBlock(article.getEvidenceJson());
        String planBlock = planJsonToPromptBlock(article.getPlanJson());
        boolean hasEvidence = hasText(article.getEvidenceJson());
        boolean hasPlan = hasText(article.getPlanJson());

        ContentPipelineService.Reviewed reviewed = pipelineService.finalize(
                topic,
                category,
                markdown,
                "standard",
                hasEvidence,
                hasPlan,
                pipelineService.hasGenerationFallback(article),
                evidenceBlock,
                planBlock);
        List<Map<String, Object>> evidenceSources = readListOfMaps(article.getEvidenceJson());
        String finalMarkdown = ensurePublishReadyLead(
                ensurePublishReadyTail(reviewed.getMarkdown(), evidenceSources), topic);
        String clickTitle = sharpenClickTitle(
                extractTitleFromMarkdown(finalMarkdown, article.getTitle()), topic);
        finalMarkdown = ensureMarkdownTitle(finalMarkdown, clickTitle);
        List<ArticleInlineImage> inlineImages = readInlineImages(article.getInlineImagesJson());
        String contentHtml = renderArticleHtml(finalMarkdown, inlineImages, article.getLayoutTheme(), category);
        // Same as generate: never re-score visuals when imageMode=none (avoids false 78 + image penalties).
        String imageMode = defaultText(article.getImageMode(), DEFAULT_IMAGE_MODE);
        boolean skipVisuals = "none".equalsIgnoreCase(imageMode);
        if (!skipVisuals) {
            ContentPipelineService.Reviewed renderedReview = pipelineService.refineAfterRender(
                    reviewed, topic, contentHtml, inlineImages);
            if (renderedReview != null) {
                reviewed = renderedReview;
            }
        }

        article.setTitle(clickTitle);
        if (!hasText(article.getDigest()) || isWeakDigest(article.getDigest(), clickTitle)) {
            article.setDigest(sharpenDigest(article.getDigest(), topic, clickTitle));
        }
        article.setContentMarkdown(finalMarkdown);
        article.setContentHtml(contentHtml);
        article.setReviewJson(reviewed.reviewAsMap() == null ? null : writeJson(reviewed.reviewAsMap()));
        List<String> risks = new ArrayList<>(readListOfStrings(article.getRiskTipsJson()));
        risks.add("已执行定向改写（按审稿意见）");
        if (reviewed.getRiskTips() != null) {
            for (String tip : reviewed.getRiskTips()) {
                if (hasText(tip) && !risks.contains(tip)) {
                    risks.add(tip);
                }
            }
        }
        article.setRiskTipsJson(writeJson(risks));
        PublishableArticleContract.Evaluation gate = pipelineService.evaluateGate(
                article, "standard", imageMode, pipelineService.hasGenerationFallback(article));
        int reviewScore = reviewed.qualityScore() == null ? 0 : reviewed.qualityScore();
        int contractScore = gate.getScore();
        article.setQualityScore(pipelineService.reconcileQualityScore(reviewScore, contractScore, gate));
        pipelineService.applyQualityState(article, gate, pipelineService.hasGenerationFallback(article));
        return view(articleRepository.save(article));
    }

    /**
     * Re-run cover + inline images only, then re-gate delivery readiness.
     */
    @Transactional
    public ContentArticleView refreshVisuals(Long userId, Long id) {
        return refreshVisualsArticle(requireArticle(userId, id));
    }

    /** Re-run visuals for any editable article from the admin console. */
    @Transactional
    public ContentArticleView refreshVisualsForAdmin(Long id) {
        return refreshVisualsArticle(requireArticleForAdminUpdate(id));
    }

    private ContentArticleView refreshVisualsArticle(ContentArticle article) {
        prepareEditable(article);
        String markdown = defaultText(article.getContentMarkdown(), "");
        if (!hasText(markdown) && !hasText(article.getContentHtml())) {
            throw new BusinessException(400, "当前草稿没有正文，无法重配图");
        }
        if (!hasText(markdown)) {
            markdown = "# " + defaultText(article.getTitle(), "草稿") + "\n\n"
                    + htmlToPlainText(article.getContentHtml());
        }
        String topic = resolveArticleTopic(article);
        String imageMode = defaultText(article.getImageMode(), DEFAULT_IMAGE_MODE);
        String coverPrompt = defaultText(article.getCoverPrompt(),
                buildCoverPrompt(topic, null, article.getCategory()));
        List<String> risks = new ArrayList<>(readListOfStrings(article.getRiskTipsJson()));
        risks.removeIf(item -> item != null && (item.contains("内联配图") || item.contains("Inline image")
                || item.contains("封面模型") || item.contains("Cover image")));
        String coverImageUrl = generateCoverImageIfNeeded(coverPrompt, imageMode, true, risks);
        List<ArticleInlineImage> inlineImages = articleVisualService.create(topic, markdown, imageMode);
        long readyInlineImages = inlineImages.stream().filter(ArticleInlineImage::ready).count();
        if (readyInlineImages < 2) {
            risks.add("内联配图不足：需要 2-3 张可用图，当前就绪 " + readyInlineImages + " 张。");
        }
        risks.add("已重新生成封面与内联图");
        article.setCoverPrompt(coverPrompt);
        article.setCoverImageUrl(coverImageUrl);
        article.setInlineImagesJson(writeJson(inlineImages));
        article.setContentHtml(renderArticleHtml(markdown, inlineImages, article.getLayoutTheme(), article.getCategory()));
        article.setRiskTipsJson(writeJson(risks));
        PublishableArticleContract.Evaluation gate = pipelineService.evaluateArticle(article);
        Integer previous = article.getQualityScore();
        article.setQualityScore(previous == null ? gate.getScore() : Math.min(previous, gate.getScore()));
        pipelineService.applyQualityState(article, gate, pipelineService.hasGenerationFallback(article));
        return view(articleRepository.save(article));
    }

    private String resolveArticleTopic(ContentArticle article) {
        List<Map<String, Object>> topics = readListOfMaps(article.getTopicsJson());
        if (topics != null) {
            for (Map<String, Object> item : topics) {
                if (item == null) {
                    continue;
                }
                String title = trimToNull(asString(item.get("title")));
                if (title != null) {
                    return title;
                }
            }
        }
        return defaultText(article.getTitle(), "今日选题");
    }

    private String evidenceJsonToPromptBlock(String evidenceJson) {
        List<Map<String, Object>> sources = readListOfMaps(evidenceJson);
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        return evidenceSourcesToPromptBlock(sources);
    }

    private String planJsonToPromptBlock(String planJson) {
        Object plan = readObject(planJson);
        if (!(plan instanceof Map)) {
            return defaultText(planJson, "");
        }
        Map<?, ?> map = (Map<?, ?>) plan;
        StringBuilder block = new StringBuilder();
        Object hook = map.get("hook");
        Object judgment = map.get("coreJudgment");
        Object cta = map.get("callToAction");
        if (hook != null && hasText(String.valueOf(hook))) {
            block.append("开头：").append(String.valueOf(hook).trim()).append("\n");
        }
        if (judgment != null && hasText(String.valueOf(judgment))) {
            block.append("核心判断：").append(String.valueOf(judgment).trim()).append("\n");
        }
        Object sections = map.get("sections");
        if (sections instanceof List) {
            block.append("小节：\n");
            for (Object section : (List<?>) sections) {
                if (section != null && hasText(String.valueOf(section))) {
                    block.append("- ").append(String.valueOf(section).trim()).append("\n");
                }
            }
        }
        if (cta != null && hasText(String.valueOf(cta))) {
            block.append("结尾互动：").append(String.valueOf(cta).trim()).append("\n");
        }
        return block.toString();
    }

    @Transactional
    public ContentArticleView update(Long userId, Long id, ContentArticleUpdateRequest req) {
        return updateArticle(requireArticle(userId, id), req);
    }

    /** Update any editable article from the site-wide admin console. */
    @Transactional
    public ContentArticleView updateForAdmin(Long id, ContentArticleUpdateRequest req) {
        return updateArticle(requireArticleForAdminUpdate(id), req);
    }

    private ContentArticleView updateArticle(ContentArticle article, ContentArticleUpdateRequest req) {
        if (req == null) {
            throw new BusinessException(400, "Article update body is required");
        }
        prepareEditable(article);
        if (hasText(req.getTitle())) {
            article.setTitle(req.getTitle().trim());
        }
        if (req.getCategory() != null) {
            article.setCategory(normalizeCategory(req.getCategory()));
        }
        article.setDigest(trimToNull(req.getDigest()));
        if (req.getContentMarkdown() != null) {
            article.setContentMarkdown(req.getContentMarkdown());
            // Keep HTML in sync when editor only sends markdown, so the gate sees the same body.
            if (!hasText(req.getContentHtml())) {
                List<ArticleInlineImage> images = readInlineImages(article.getInlineImagesJson());
                article.setContentHtml(renderArticleHtml(
                        req.getContentMarkdown(), images, article.getLayoutTheme(), article.getCategory()));
            }
        }
        if (hasText(req.getContentHtml())) {
            article.setContentHtml(req.getContentHtml());
        }
        String coverUrl = trimToNull(req.getCoverImageUrl());
        if (coverUrl != null && (coverUrl.startsWith("http://")
                || coverUrl.startsWith("https://") || coverUrl.startsWith("data:image/"))) {
            coverUrl = generatedImageService.persistImageReference(coverUrl);
        } else if (coverUrl != null && !coverUrl.equals(article.getCoverImageUrl())) {
            throw new BusinessException(400, "封面必须是有效的图片地址");
        }
        article.setCoverImageUrl(coverUrl);
        // Re-run publish contract after human edit so score/error/automation stay truthful.
        PublishableArticleContract.Evaluation gate = pipelineService.evaluateArticle(article);
        article.setQualityScore(gate.getScore());
        pipelineService.applyQualityState(article, gate, pipelineService.hasGenerationFallback(article));
        return view(articleRepository.save(article));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        deleteArticle(requireArticle(userId, id));
    }

    /** Delete any article from the site-wide admin console. */
    @Transactional
    public void deleteForAdmin(Long id) {
        deleteArticle(requireArticleForAdminUpdate(id));
    }

    private void deleteArticle(ContentArticle article) {
        articleRepository.delete(article);
    }

    @Transactional
    public Map<String, Object> createWechatDraft(Long userId, Long id) {
        return createWechatDraftResult(requireArticleForUpdate(userId, id));
    }

    /** Create a WeChat draft for any article from the site-wide admin console. */
    @Transactional
    public Map<String, Object> createWechatDraftForAdmin(Long id) {
        return createWechatDraftResult(requireArticleForAdminUpdate(id));
    }

    private Map<String, Object> createWechatDraftResult(ContentArticle source) {
        ContentArticle article = createWechatDraftArticle(source);
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("mediaId", article.getWechatMediaId());
        draft.put("url", article.getWechatUrl());
        draft.put("mode", isLocalDraft(article) ? "local" : "wechat");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("article", view(article));
        result.put("draft", draft);
        return result;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public Map<String, Object> publishWechat(Long userId, Long id) {
        return publishWechatArticleResult(requireArticleForUpdate(userId, id));
    }

    /** Publish any eligible article from the site-wide admin console. */
    @Transactional(noRollbackFor = BusinessException.class)
    public Map<String, Object> publishWechatForAdmin(Long id) {
        return publishWechatArticleResult(requireArticleForAdminUpdate(id));
    }

    private Map<String, Object> publishWechatArticleResult(ContentArticle source) {
        if (!freePublishEnabled()) {
            throw new BusinessException(403, "当前公众号是订阅号，未启用 freepublish 权限，请先创建微信草稿后在公众平台人工发布");
        }
        ContentArticle article = publishWechatArticle(source);
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("mediaId", article.getWechatMediaId());
        draft.put("url", article.getWechatUrl());
        draft.put("mode", isLocalDraft(article) ? "local" : "wechat");
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
        return automationForArticle(articleId == null ? null : requireArticle(userId, articleId));
    }

    /** Read automation history for any article from the site-wide admin console. */
    public ContentAutomationView automationForAdmin(Long articleId) {
        return automationForArticle(articleId == null ? null : requireArticleForAdmin(articleId));
    }

    private ContentAutomationView automationForArticle(ContentArticle article) {
        if (article == null) {
            return ContentAutomationView.empty();
        }
        Object automation = readObject(article.getAutomationJson());
        if (automation instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) automation;
            Object qualityGate = map.get("qualityGate");
            return new ContentAutomationView(
                    asString(map.get("currentStage")),
                    asList(map.get("logs")),
                    asList(map.get("jobs")),
                    asList(map.get("publishRecords")),
                    asList(map.get("artifacts")),
                    qualityGate instanceof Map ? (Map<String, Object>) qualityGate : null
            );
        }
        return automationBuilder.buildAutomation(article, article.getErrorMessage() == null ? null : article.getErrorMessage());
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public ContentAutomationView retryAutomationJob(Long userId, String jobId) {
        return retryAutomationJob(requireArticleForUpdate(userId, retryArticleIdOrThrow(jobId)), jobId);
    }

    /** Retry WeChat delivery for any article from the site-wide admin console. */
    @Transactional(noRollbackFor = BusinessException.class)
    public ContentAutomationView retryAutomationJobForAdmin(String jobId) {
        return retryAutomationJob(requireArticleForAdminUpdate(retryArticleIdOrThrow(jobId)), jobId);
    }

    private ContentAutomationView retryAutomationJob(ContentArticle article, String jobId) {
        if (!hasText(jobId)) {
            throw new BusinessException(400, "jobId is required");
        }
        String token = jobId.trim();
        if (!hasText(article.getErrorMessage())
                && !ContentArticle.STATUS_PUBLISH_SUBMITTED.equals(article.getStatus())) {
            throw new BusinessException(400, "Article has no failed delivery to retry");
        }
        String retryStage = retryStage(token, article);
        if ("publish".equals(retryStage)) {
            article = publishWechatArticle(article);
        } else {
            article = createWechatDraftArticle(article);
        }
        return automationBuilder.buildAutomation(article, article.getErrorMessage(), retryStage);
    }

    private Long retryArticleIdOrThrow(String jobId) {
        if (!hasText(jobId)) {
            throw new BusinessException(400, "jobId is required");
        }
        Long articleId = retryArticleId(jobId.trim());
        if (articleId == null) {
            throw new BusinessException(400, "jobId must contain a valid article id");
        }
        return articleId;
    }

    private Long retryArticleId(String jobId) {
        String candidate = jobId;
        int separator = candidate.indexOf('-');
        if (separator > 0) {
            candidate = candidate.substring(0, separator);
        }
        try {
            long value = Long.parseLong(candidate);
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String retryStage(String jobId, ContentArticle article) {
        String normalized = jobId.toLowerCase();
        if (normalized.contains("publish") && !normalized.contains("wechat-draft")) {
            return "publish";
        }
        if (normalized.contains("wechat") || normalized.contains("draft")) {
            return "wechat_draft";
        }
        Object automation = readObject(article.getAutomationJson());
        if (automation instanceof Map) {
            String currentStage = asString(((Map<?, ?>) automation).get("currentStage"));
            if ("publish".equalsIgnoreCase(currentStage)) {
                return "publish";
            }
        }
        return "wechat_draft";
    }

    private ContentArticle markQualityGateBlocked(ContentArticle article) {
        article.setStatus(ContentArticle.STATUS_DRAFT);
        String length = "standard";
        PublishableArticleContract.Evaluation gate = pipelineService.evaluateGate(
                article, length, article.getImageMode(), pipelineService.hasGenerationFallback(article));
        if (article.getQualityScore() == null) {
            article.setQualityScore(gate.getScore());
        }
        pipelineService.applyQualityState(article, gate, pipelineService.hasGenerationFallback(article));
        return articleRepository.save(article);
    }

    private ContentArticle createWechatDraftArticle(ContentArticle article) {
        if (hasText(article.getWechatMediaId())
                && !article.getWechatMediaId().startsWith("local-")
                && (ContentArticle.STATUS_WECHAT_DRAFT.equals(article.getStatus())
                || ContentArticle.STATUS_PUBLISH_SUBMITTED.equals(article.getStatus())
                || ContentArticle.STATUS_PUBLISHED.equals(article.getStatus()))
                && !hasText(article.getErrorMessage())) {
            return article;
        }
        if (pipelineService.blocksWechatDraft(article)) {
            throw new BusinessException(400, hasText(article.getErrorMessage())
                    ? article.getErrorMessage()
                    : "发布门禁未通过，请先改稿或重新生成后再创建微信草稿");
        }
        String appId = config(WechatOfficialAccountClient.CFG_APP_ID);
        String appSecret = config(WechatOfficialAccountClient.CFG_APP_SECRET);
        if (!wechatClient.configured(appId, appSecret)) {
            return markLocalDraft(article, "缺少 wechat.appId / wechat.appSecret，已保留为本站本地草稿");
        }
        try {
            List<ArticleInlineImage> inlineImages = readInlineImages(article.getInlineImagesJson());
            uploadWechatInlineImages(inlineImages, appId, appSecret);
            article.setInlineImagesJson(writeJson(inlineImages));
            article.setContentHtml(renderArticleHtml(
                    article.getContentMarkdown(),
                    inlineImages,
                    article.getLayoutTheme(),
                    article.getCategory()));
            PublishableArticleContract.Evaluation synchronizedGate = pipelineService.evaluateArticle(article);
            if (synchronizedGate.blocksPublish()) {
                throw new BusinessException(400, "Quality gate blocked after WeChat image synchronization: "
                        + String.join("; ", synchronizedGate.getIssues()));
            }
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
            article.setAutomationJson(writeJson(automationBuilder.buildAutomation(article, null)));
            return articleRepository.save(article);
        } catch (BusinessException e) {
            markWechatFailure(article, e.getMessage(), "wechat_draft");
            return markLocalDraft(article, "微信草稿创建失败，已保留为本站本地草稿：" + e.getMessage());
        }
    }

    private void uploadWechatInlineImages(List<ArticleInlineImage> images,
                                          String appId,
                                          String appSecret) {
        if (images == null || images.size() < 2) {
            throw new BusinessException(400, "Publish-ready WeChat draft requires 2-3 inline images");
        }
        for (ArticleInlineImage image : images) {
            if (image == null || !image.ready()) {
                throw new BusinessException(400, "Inline image is not ready: "
                        + (image == null ? "unknown" : image.getSlotId()));
            }
            byte[] bytes = articleVisualService.readValidatedBytes(image);
            if (bytes == null) {
                throw new BusinessException(400, "Inline image changed or cannot be read: " + image.getSlotId());
            }
            WechatOfficialAccountClient.ContentImageResult uploaded = wechatClient.uploadContentImage(
                    appId,
                    appSecret,
                    bytes,
                    inlineImageFilename(image),
                    image.getMimeType());
            image.setWechatUrl(uploaded.getUrl());
        }
    }

    private String inlineImageFilename(ArticleInlineImage image) {
        String extension = "image/jpeg".equalsIgnoreCase(image.getMimeType()) ? ".jpg" : ".png";
        return defaultText(image.getSlotId(), "content-image") + extension;
    }

    private List<ArticleInlineImage> readInlineImages(String json) {
        if (!hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ArticleInlineImage>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private ContentArticle publishWechatArticle(ContentArticle article) {
        if (ContentArticle.STATUS_PUBLISHED.equals(article.getStatus())) {
            return article;
        }
        if (ContentArticle.STATUS_PUBLISH_SUBMITTED.equals(article.getStatus())
                && hasText(article.getWechatPublishId())) {
            return refreshPublishStatus(article);
        }
        if (pipelineService.blocksWechatDraft(article)) {
            throw new BusinessException(400, "质量门阻断：评分低于 80，未执行自动发布，请先人工修订");
        }
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
            article.setStatus(ContentArticle.STATUS_PUBLISH_SUBMITTED);
            article.setWechatPublishId(publish.getPublishId());
            article.setWechatUrl(null);
            article.setErrorMessage(null);
            article.setAutomationJson(writeJson(automationBuilder.buildAutomation(article, null, "publish")));
            ContentArticle submitted = articleRepository.save(article);
            try {
                return refreshPublishStatus(submitted);
            } catch (BusinessException statusError) {
                // Submission was accepted. Leave it pending and let the reconciler retry status.
                return submitted;
            }
        } catch (BusinessException e) {
            markWechatFailure(article, e.getMessage(), "publish");
            throw e;
        }
    }

    private ContentArticle markLocalDraft(ContentArticle article, String message) {
        article.setStatus(ContentArticle.STATUS_DRAFT);
        if (article.getWechatMediaId() != null && article.getWechatMediaId().startsWith("local-")) {
            article.setWechatMediaId(null);
        }
        if (!hasText(article.getWechatUrl())) {
            article.setWechatUrl("/admin/content?articleId=" + article.getId());
        }
        article.setErrorMessage(message);
        article.setAutomationJson(writeJson(automationBuilder.buildAutomation(article, message, "wechat_draft")));
        return articleRepository.save(article);
    }

    @Scheduled(fixedDelayString = "${content.wechat.publish-reconcile-ms:60000}", initialDelay = 45000)
    public void reconcileSubmittedWechatPublishes() {
        List<ContentArticle> pending = articleRepository
                .findTop50ByStatusAndWechatPublishIdIsNotNullOrderByUpdatedAtAsc(
                        ContentArticle.STATUS_PUBLISH_SUBMITTED);
        for (ContentArticle article : pending) {
            try {
                refreshPublishStatus(article);
            } catch (Exception ignored) {
                // A later tick will retry transient token/network failures.
            }
        }
    }

    private ContentArticle refreshPublishStatus(ContentArticle article) {
        WechatOfficialAccountClient.PublishStatusResult result = wechatClient.getPublishStatus(
                config(WechatOfficialAccountClient.CFG_APP_ID),
                config(WechatOfficialAccountClient.CFG_APP_SECRET),
                article.getWechatPublishId());
        if (result.published()) {
            article.setStatus(ContentArticle.STATUS_PUBLISHED);
            article.setWechatUrl(trimToNull(result.getArticleUrl()));
            article.setErrorMessage(null);
            article.setAutomationJson(writeJson(automationBuilder.buildAutomation(article, null, "publish")));
        } else if (result.pending()) {
            article.setStatus(ContentArticle.STATUS_PUBLISH_SUBMITTED);
            article.setErrorMessage(null);
            article.setAutomationJson(writeJson(automationBuilder.buildAutomation(article, null, "publish")));
        } else {
            article.setStatus(ContentArticle.STATUS_PUBLISH_FAILED);
            String detail = hasText(result.getFailErrorCode())
                    ? " (" + result.getFailErrorCode() + ")" : "";
            article.setErrorMessage("WeChat publish failed with status " + result.getStatus() + detail);
            article.setAutomationJson(writeJson(automationBuilder.buildAutomation(
                    article, article.getErrorMessage(), "publish")));
        }
        return articleRepository.save(article);
    }

    private void markWechatFailure(ContentArticle article, String message, String stage) {
        article.setErrorMessage(message);
        article.setAutomationJson(writeJson(automationBuilder.buildAutomation(article, message, stage)));
        articleRepository.save(article);
    }

    private String resolveThumbMediaId(ContentArticle article, String appId, String appSecret) {
        byte[] coverBytes = articleVisualService.readCoverBytes(article.getCoverImageUrl());
        if (coverBytes != null && coverBytes.length > 0) {
            String persisted = generatedImageService.persistImageBytes(coverBytes, "image/png");
            if (!hasText(persisted)) {
                throw new BusinessException(502, "文章封面图床上传失败");
            }
            return wechatClient.uploadPermanentImage(
                    appId,
                    appSecret,
                    coverBytes,
                    articleVisualService.coverFilename(article.getCoverImageUrl())).getMediaId();
        }
        String configured = trimToNull(config("wechat.coverMediaId"));
        if (configured != null) {
            return configured;
        }
        byte[] defaultCover = articleVisualService.generateDefaultCoverBytes(article.getTitle(), article.getCategory());
        String persistedDefault = generatedImageService.persistImageBytes(defaultCover, "image/png");
        if (!hasText(persistedDefault)) {
            throw new BusinessException(502, "默认文章封面图床上传失败");
        }
        WechatOfficialAccountClient.MaterialResult uploaded = wechatClient.uploadPermanentImage(
                appId,
                appSecret,
                defaultCover,
                "zaoyibu-default-cover.png"
        );
        configService.upsertByKey("wechat.coverMediaId", uploaded.getMediaId(), "Default WeChat cover media_id uploaded by content factory.");
        return uploaded.getMediaId();
    }

    private Map<String, Object> selectAgentTopic(Long userId, ContentAgentRunRequest req, String category) {
        String manualTopic = trimToNull(req.getTopic());
        if (manualTopic != null) {
            Map<String, Object> topic = fallbackAgentTopic(category, req.getInstruction());
            topic.put("title", manualTopic);
            topic.put("source", "agent_manual");
            topic.put("sourceName", "Agent 手动主题");
            return topic;
        }
        // Editorial decision (选题聚类 + 编辑决策): let the editor pick from live hot-topic
        // candidates with an explicit rationale. Falls through to the single-pick agent below
        // when the decision layer is disabled, declines the batch, or is unavailable.
        Map<String, Object> editorPick = selectViaEditorialDecision(userId, req, category);
        if (editorPick != null) {
            return editorPick;
        }
        if (hasText(config("ai.chat.baseUrl"))) {
            try {
                List<AiChatUpstreamClient.ChatMessage> messages = new ArrayList<>();
                messages.add(new AiChatUpstreamClient.ChatMessage("system",
                        "你是公众号「早一步信息差」的自动选题 Agent。只输出 JSON，不要 Markdown 代码块。"));
                messages.add(new AiChatUpstreamClient.ChatMessage("user",
                        buildTopicPrompt(category, req.getInstruction(), recentTitles(userId))));
                AiChatUpstreamClient.ChatCompletionResult result = aiChatUpstreamClient.completeQuick(
                        config("ai.chat.baseUrl"),
                        config("ai.chat.apiKey"),
                        defaultText(config("ai.chat.defaultModel"), "mimo-v2.5-pro"),
                        messages
                );
                Map<String, Object> parsed = tryReadMap(extractJsonObject(result.getContent()));
                if (parsed != null && hasText(asString(parsed.get("title")))
                        && !topicCandidateService.isDuplicateTopic(asString(parsed.get("title")), recentTitles(userId))) {
                    return normalizeAgentTopic(parsed, category);
                }
            } catch (Exception ignored) {
                // Fallback below keeps the automation agent usable when topic selection times out.
            }
        }
        return fallbackAgentTopic(category, req.getInstruction());
    }

    /**
     * Editorial decision path: pull today's ranked hot-topic candidates for the column and let
     * {@link EditorialDecisionService} cluster and choose one (or skip). Returns {@code null} when
     * the decision layer is disabled, has nothing usable, or deliberately skips the batch — the
     * caller then falls back to the standalone topic agent.
     */
        private Map<String, Object> selectViaEditorialDecision(Long userId, ContentAgentRunRequest req, String category) {
        List<Map<String, Object>> candidates;
        try {
            candidates = hotTopicService.hotTopics(24, category).getItems();
        } catch (Exception e) {
            return null;
        }
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<String> recent = recentTitles(userId);
        TopicCandidateService.ScoredCandidates scored = topicCandidateService.score(candidates, category, recent);
        List<Map<String, Object>> usable = scored.topUsable(12);
        if (usable.isEmpty()) {
            return null;
        }
        EditorialDecisionService.Decision decision =
                editorialDecisionService.decide(usable, category, req.getInstruction(), recent);
        Map<String, Object> chosen;
        if (decision != null && !decision.isSkip() && decision.getTopic() != null) {
            chosen = normalizeAgentTopic(decision.getTopic(), category);
            chosen.put("decisionMode", "llm-editor");
        } else {
            chosen = normalizeAgentTopic(scored.best(), category);
            chosen.put("decisionMode", "local-score");
            chosen.put("reason", defaultText(asString(chosen.get("reason")), "LLM 主编决策不可用，使用本地评分最高且未重复的候选"));
        }
        chosen.put("topicScores", scored.getScoreCards());
        chosen.put("candidateCount", candidates.size());
        chosen.put("usableCandidateCount", usable.size());
        return chosen;
    }

    private String buildTopicPrompt(String category, String instruction, List<String> recentTitles) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请为订阅号「早一步信息差」自动选择 1 个今天值得写的公众号选题。\n");
        prompt.append("账号定位：信息差型热点解读。栏目：").append(category).append("。\n");
        prompt.append("偏好：口语化，像朋友聊天；拒绝正式、学术、研报腔。\n");
        prompt.append("参考方向：科技 / 互联网、教育 / 职场、财政金融，以及普通人容易忽略的信息差。\n");
        if (hasText(instruction)) {
            prompt.append("额外要求：").append(instruction.trim()).append("\n");
        }
        if (recentTitles != null && !recentTitles.isEmpty()) {
            prompt.append("以下是最近已经写过的标题，请避免重复选题、也不要换个说法写同一件事：\n");
            for (String title : recentTitles) {
                prompt.append("- ").append(title).append("\n");
            }
        }
        prompt.append("请输出 JSON：title,summary,angle,audience,tags,reason。tags 是字符串数组。\n");
        prompt.append("选题要具体，不要写成泛泛的行业观察。");
        return prompt.toString();
    }

    /**
     * Recent article titles for the given user, used to steer the topic Agent away from
     * repeating stories. Window size ({@code content.autopilot.dedupDays}) and cap are
     * config-driven; failures degrade to an empty list so selection still proceeds.
     */
    private List<String> recentTitles(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        int dedupDays = 3;
        if (dedupDays <= 0) {
            return Collections.emptyList();
        }
        try {
            return articleRepository.findRecentTitles(
                    userId,
                    LocalDateTime.now().minusDays(dedupDays),
                    PageRequest.of(0, 30));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

        private Map<String, Object> normalizeAgentTopic(Map<String, Object> parsed, String category) {
        Map<String, Object> topic = parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        topic.put("id", defaultText(asString(topic.get("id")), "agent-" + UUID.randomUUID().toString()));
        topic.put("source", defaultText(asString(topic.get("source")), "agent"));
        topic.put("sourceName", defaultText(asString(topic.get("sourceName")), "自动选题 Agent"));
        topic.put("rank", topic.get("rank") == null ? 1 : topic.get("rank"));
        topic.put("title", defaultText(asString(topic.get("title")), fallbackTopicTitle(category)));
        topic.put("summary", trimToNull(asString(topic.get("summary"))));
        topic.put("angle", defaultText(asString(topic.get("angle")), defaultAgentAngle(category)));
        topic.put("audience", defaultText(asString(topic.get("audience")), "想快速看懂热点、不想被标题带节奏的读者"));
        topic.put("reason", trimToNull(asString(topic.get("reason"))));
        topic.put("tags", readStringList(topic.get("tags")));
        topic.put("capturedAt", defaultText(asString(topic.get("capturedAt")), LocalDateTime.now().toString()));
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

    /**
     * Markdown → themed WeChat HTML (inline styles; layoutTheme clean/warm/magazine).
     */
    private String renderArticleHtml(String markdown,
                                     List<ArticleInlineImage> images,
                                     String layoutTheme,
                                     String category) {
        return markdownToHtml(
                markdown,
                images == null ? Collections.emptyList() : images,
                defaultText(layoutTheme, DEFAULT_LAYOUT),
                defaultText(category, DEFAULT_CATEGORY));
    }

    private ContentArticle requireArticle(Long userId, Long id) {
        return articleRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Article not found"));
    }

    private ContentArticle requireArticleForAdmin(Long id) {
        return articleRepository.findByIdForAdmin(id)
                .orElseThrow(() -> new BusinessException(404, "Article not found"));
    }

    private ContentArticle requireArticleForUpdate(Long userId, Long id) {
        return articleRepository.findLockedByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Article not found"));
    }

    private ContentArticle requireArticleForAdminUpdate(Long id) {
        return articleRepository.findLockedByIdForAdmin(id)
                .orElseThrow(() -> new BusinessException(404, "Article not found"));
    }

    private boolean isLocalDraft(ContentArticle article) {
        return article == null || !ContentArticle.STATUS_WECHAT_DRAFT.equals(article.getStatus())
                && !ContentArticle.STATUS_PUBLISH_SUBMITTED.equals(article.getStatus())
                && !ContentArticle.STATUS_PUBLISHED.equals(article.getStatus());
    }

    private void prepareEditable(ContentArticle article) {
        if (ContentArticle.STATUS_PUBLISHED.equals(article.getStatus())
                || ContentArticle.STATUS_PUBLISH_SUBMITTED.equals(article.getStatus())) {
            throw new BusinessException(409, "Submitted or published articles cannot be edited");
        }
        if (ContentArticle.STATUS_WECHAT_DRAFT.equals(article.getStatus())) {
            article.setStatus(ContentArticle.STATUS_DRAFT);
            article.setWechatMediaId(null);
            article.setWechatPublishId(null);
            article.setWechatUrl(null);
        }
    }

    private ContentArticleView view(ContentArticle article) {
        return ContentArticleView.from(
                article,
                readListOfMaps(article.getTopicsJson()),
                readListOfStrings(article.getTagsJson()),
                readListOfStrings(article.getRiskTipsJson()),
                readObject(article.getAutomationJson()),
                readObject(article.getPlanJson()),
                readListOfMaps(article.getEvidenceJson()),
                readObject(article.getReviewJson()),
                readListOfMaps(article.getInlineImagesJson())
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
        view.setInlineImages(Collections.emptyList());
        view.setTopics(Collections.emptyList());
        view.setTags(Collections.emptyList());
        view.setRiskTips(Collections.emptyList());
        view.setModel(article.getModel());
        view.setCategory(article.getCategory());
        view.setLayoutTheme(article.getLayoutTheme());
        view.setImageMode(article.getImageMode());
        view.setAutomation(null);
        view.setQualityScore(article.getQualityScore());
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

    private List<Map<String, Object>> fallbackEvidenceSources(List<Map<String, Object>> topics, String topicTitle) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Map<String, Object> item : topics) {
            if (item == null) {
                continue;
            }
            String title = defaultText(asString(item.get("title")), topicTitle);
            String url = trimToNull(asString(item.get("url")));
            String sourceName = defaultText(asString(item.get("sourceName")), "Selected topic source");
            if (!hasText(title) && !hasText(url)) {
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("title", title);
            if (hasText(url)) {
                source.put("url", url);
            }
            source.put("query", sourceName);
            source.put("type", "selected_topic");
            sources.add(source);
            if (sources.size() >= 3) {
                break;
            }
        }
        return sources;
    }

    private String evidenceSourcesToPromptBlock(List<Map<String, Object>> sources) {
        StringBuilder block = new StringBuilder();
        int index = 1;
        for (Map<String, Object> source : sources) {
            if (source == null) {
                continue;
            }
            String title = trimToNull(asString(source.get("title")));
            String url = trimToNull(asString(source.get("url")));
            String query = trimToNull(asString(source.get("query")));
            if (!hasText(title) && !hasText(url)) {
                continue;
            }
            block.append(index++).append(". ").append(defaultText(title, url));
            if (hasText(query)) {
                block.append(" (source: ").append(query).append(")");
            }
            if (hasText(url)) {
                block.append(" ").append(url);
            }
            block.append("\n");
        }
        return block.toString();
    }

    private String generateCoverImageIfNeeded(String coverPrompt,
                                              String imageMode,
                                              Boolean generateCover,
                                              List<String> riskTips) {
        if (Boolean.FALSE.equals(generateCover) || "none".equalsIgnoreCase(defaultText(imageMode, DEFAULT_IMAGE_MODE))) {
            return null;
        }
        if (hasText(config(ImageService.CFG_BASE_URL)) && hasText(config(ImageService.CFG_API_KEY))) {
            String generatedCover = articleVisualService.generateCoverImageUrl(coverPrompt);
            if (hasText(generatedCover)) {
                return generatedCover;
            }
        }
        ArticleInlineImage openCover = articleVisualService.createCoverFallback(coverPrompt, coverPrompt);
        if (openCover != null && openCover.ready() && hasText(openCover.getSourceUrl())) {
            riskTips.add("Cover image generation service was unavailable; used a commercially reusable open-image fallback with attribution: "
                    + defaultText(openCover.getAttribution(), openCover.getSourcePageUrl()));
            return openCover.getSourceUrl();
        }
        riskTips.add("封面模型未配置或未返回图片，已跳过封面生成。");
        return null;
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

    private String config(String key) {
        return configService.getValue(key).orElse("");
    }

    private boolean usableImageKey(String value) {
        return hasText(value) && !"sk-xxx".equalsIgnoreCase(value.trim())
                && !value.trim().toLowerCase(Locale.ROOT).startsWith("replace-with-");
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

    private String normalizeCategory(String value) {
        String category = defaultText(value, DEFAULT_CATEGORY);
        return category.length() > 80 ? category.substring(0, 80) : category;
    }

}
