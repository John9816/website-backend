package com.example.website.service.content;

import com.example.website.common.BusinessException;
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
import com.example.website.service.AiChatUpstreamClient;
import com.example.website.service.GeneratedImageService;
import com.example.website.service.ImageService;
import com.example.website.service.SysConfigService;
import com.example.website.service.WechatOfficialAccountClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.example.website.service.content.ContentTextUtils.asList;
import static com.example.website.service.content.ContentTextUtils.asString;
import static com.example.website.service.content.ContentTextUtils.countMatches;
import static com.example.website.service.content.ContentTextUtils.defaultText;
import static com.example.website.service.content.ContentTextUtils.escape;
import static com.example.website.service.content.ContentTextUtils.extractJsonObject;
import static com.example.website.service.content.ContentTextUtils.firstString;
import static com.example.website.service.content.ContentTextUtils.firstText;
import static com.example.website.service.content.ContentTextUtils.hasText;
import static com.example.website.service.content.ContentTextUtils.hashText;
import static com.example.website.service.content.ContentTextUtils.htmlToPlainText;
import static com.example.website.service.content.ContentTextUtils.readStringList;
import static com.example.website.service.content.ContentTextUtils.trimToNull;
import static com.example.website.service.content.MarkdownHtmlConverter.markdownToHtml;

@Service
@RequiredArgsConstructor
public class ContentArticleService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_CATEGORY = "科技 / 互联网";
    private static final String DEFAULT_LAYOUT = "clean";
    private static final String DEFAULT_IMAGE_MODE = "generate";
    /** Phrases that kill open-rate or trip the publish gate — rewrite title if present. */
    private static final List<String> TITLE_BANNED_PHRASES = Arrays.asList(
            "一文看懂", "一文读懂", "深度解析", "全面解读",
            "和你有什么关系", "和你有啥关系", "背后的信息差", "值得关注",
            "你怎么看", "该怎么看",
            "震惊", "炸裂", "彻底变天", "全网都", "没人告诉你", "再不知道就晚了", "万万没想到"
    );

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

        boolean autopilotOn = "true".equalsIgnoreCase(config("content.autopilot.enabled"))
                && hasText(config("content.autopilot.userId"));
        configs.add(new ContentStatusConfigView("content.autopilot.enabled", "自动流水线运行中", autopilotOn));
        configs.add(new ContentStatusConfigView("content.autopilot.autoPublish", "自动群发（需服务号）",
                "true".equalsIgnoreCase(config("content.autopilot.autoPublish"))));

        return new ContentFactoryStatusView(
                hasText(aiBase) && hasText(aiKey),
                hasText(imageBase) && hasText(imageKey),
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
        ContentGenerationResult generated = generateArticleContent(
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
        ContentArticle article = requireArticle(userId, id);
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
        ContentArticle article = requireArticle(userId, id);
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

    public ContentAutomationView retryAutomationJob(Long userId, String jobId) {
        return ContentAutomationView.empty();
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
            article.setStatus(ContentArticle.STATUS_PUBLISHED);
            article.setWechatPublishId(publish.getPublishId());
            article.setWechatUrl(null);
            article.setErrorMessage(null);
            article.setAutomationJson(writeJson(automationBuilder.buildAutomation(article, null)));
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
        article.setAutomationJson(writeJson(automationBuilder.buildAutomation(article, message)));
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

    /**
     * If the draft jumps from H1 straight into H2 without a ≥40-char lead, inject a concrete
     * scene opener so the deterministic text gate does not fail "Opening lacks…".
     * Lead is topic-bound (hot-topic rewrite), never a generic AI-productivity anecdote.
     */
    private String ensurePublishReadyLead(String markdown, String topic) {
        String result = defaultText(markdown, "").trim();
        if (!hasText(result)) {
            return result;
        }
        // Drop previously injected generic AI-meeting lead that does not match the topic.
        result = result.replaceAll(
                "(?s)\\n\\n上周三下午六点，会议室的灯还没关。同事合上笔记本说「我用 AI 二十分钟交完了」[^\\n]*",
                "");
        String[] parts = result.split("\\n\\s*\\n");
        for (String part : parts) {
            String raw = defaultText(part, "").trim();
            if (raw.matches("(?s)^#{1,6}\\s+\\S[^\\n]*$")) {
                continue; // pure H1/H2 — not an opening body paragraph
            }
            String text = part.replaceAll("(?m)^#{1,6}\\s*", "")
                    .replaceAll("(?m)^>\\s*", "")
                    .replaceAll("\\s+", "")
                    .trim();
            if (text.length() >= 40) {
                return ensureH2Budget(result);
            }
            if (text.length() >= 10) {
                break;
            }
        }
        String sceneTopic = defaultText(topic, "这件事").trim();
        if (sceneTopic.length() > 28) {
            sceneTopic = sceneTopic.substring(0, 28);
        }
        String lead = "热搜刚刷到「" + sceneTopic + "」时，评论区已经吵成两派："
                + "一边喊终于有人管了，一边觉得罚再多也跟自己无关。"
                + "真正的信息差不在标题数字，而在这件事会不会改掉你下一次下单、求职、做选择时的默认路径——下面只拆能立刻用的判断。";
        int firstBreak = result.indexOf("\n\n");
        if (result.startsWith("#") && firstBreak > 0) {
            result = result.substring(0, firstBreak) + "\n\n" + lead + result.substring(firstBreak);
        } else {
            result = lead + "\n\n" + result;
        }
        return ensureH2Budget(result);
    }

    /**
     * Publish contract allows at most 7 H2s; demote extras to bold lines so hot-topic drafts
     * are not blocked solely for over-sectioning.
     */
    private String ensureH2Budget(String markdown) {
        String result = defaultText(markdown, "");
        if (!hasText(result)) {
            return result;
        }
        String[] lines = result.split("\n", -1);
        int h2 = 0;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.matches("^##\\s+\\S.*") && !line.startsWith("###")) {
                h2++;
                if (h2 > 7) {
                    String title = line.replaceFirst("^##\\s+", "").trim();
                    out.append("**").append(title).append("**");
                } else {
                    out.append(line);
                }
            } else {
                out.append(line);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Reader-facing WeChat body only: ensure a closing interaction, and strip any
     * 参考资料 / 资料来源 / 延伸阅读 section the model may have appended.
     * Evidence stays in evidenceJson for editors — never in the published prose.
     */
    private String ensurePublishReadyTail(String markdown, List<Map<String, Object>> evidenceSources) {
        String result = stripReferenceSections(defaultText(markdown, "").trim());
        if (!hasText(result)) {
            return result;
        }
        if (!containsAny(result, "\u7559\u8a00", "\u8bc4\u8bba\u533a", "\u6b22\u8fce\u804a\u804a", "\u4f60\u89c9\u5f97", "\u4f60\u600e\u4e48\u770b",
                "\u70b9\u4e2a\u5173\u6ce8", "\u70b9\u5173\u6ce8", "\u6b22\u8fce\u5173\u6ce8")) {
            result += "\n\n---\n\n" + "\u5982\u679c\u4f60\u4e5f\u5173\u6ce8\u8fd9\u4e2a\u8bdd\u9898\uff0c\u6b22\u8fce\u7559\u8a00\u804a\u804a\uff1a\u4f60\u89c9\u5f97\u8fd9\u91cc\u9762\u6700\u5bb9\u6613\u88ab\u5ffd\u7565\u7684\u4fe1\u606f\u5dee\u662f\u4ec0\u4e48\uff1f";
        }
        return result.trim();
    }

    /** Remove ## 参考资料 / 资料来源 / 延伸阅读 (and English equivalents) through end-of-doc or next H2. */
    private String stripReferenceSections(String markdown) {
        String result = defaultText(markdown, "");
        if (!hasText(result)) {
            return result;
        }
        // Drop from a references-like H2 to the next H2 or EOF.
        result = result.replaceAll(
                "(?ms)^##\\s*(参考资料|参考来源|资料来源|信息来源|延伸阅读|References|Sources?)\\s*$.*?(?=^##\\s+|\\z)",
                "");
        // Also drop trailing "参考资料" blocks that use bold headers without ##.
        result = result.replaceAll(
                "(?ms)^\\*\\*(参考资料|资料来源|延伸阅读)\\*\\*\\s*$.*?(?=^##\\s+|\\z)",
                "");
        return result.replaceAll("\n{3,}", "\n\n").trim();
    }

    private boolean containsAny(String text, String... needles) {
        if (!hasText(text)) {
            return false;
        }
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
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

    private List<String> defaultTags(String category) {
        List<String> tags = new ArrayList<>();
        tags.add("内容工厂");
        tags.add(category);
        return tags;
    }

    private List<String> defaultRiskTips() {
        // Must be mutable: callers append tips after a successful AI parse (singletonList throws UOE).
        List<String> tips = new ArrayList<>(2);
        tips.add("早一步信息差为订阅号，当前仅生成微信草稿；发布前请人工复核事实、标题、配图和平台规则。");
        return tips;
    }

    private ContentGenerationResult generateArticleContent(String topic,
                                                           String category,
                                                           ContentArticleGenerateRequest payload,
                                                           String length,
                                                           String evidenceBlock,
                                                           String planBlock) {
        String requestedModel = defaultText(payload.getModel(), config("ai.chat.defaultModel"));
        List<String> riskTips = new ArrayList<>(defaultRiskTips());
        if (!hasText(config("ai.chat.baseUrl"))) {
            riskTips.add("Article model baseUrl is not configured; local template fallback was used.");
            return fallbackGeneration(topic, category, payload, length, requestedModel, riskTips);
        }

        String prompt = buildArticlePrompt(topic, category, payload, length, evidenceBlock, planBlock);
        List<AiChatUpstreamClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new AiChatUpstreamClient.ChatMessage("system",
                "你是微信公众号增长主笔，目标是打开率、完读率、留言和关注。只返回严格 JSON，不要 Markdown 围栏。"
                        + "输出让人想点开、想读完、想转发、想关注的正文；禁止提纲、写作教学、信息差方法论。"));
        messages.add(new AiChatUpstreamClient.ChatMessage("user", prompt));

        List<String> attemptedErrors = new ArrayList<>();
        List<String> baseUrls = writerBaseUrlCandidates();
        boolean sawConnectivityFailure = false;
        for (String baseUrl : baseUrls) {
            String apiKey = writerApiKeyForBase(baseUrl);
            for (String model : writerModelCandidates(requestedModel)) {
                try {
                    AiChatUpstreamClient.ChatCompletionResult result = aiChatUpstreamClient.complete(
                            baseUrl,
                            apiKey,
                            model,
                            messages
                    );
                    ContentGenerationResult parsed = parseGenerationResult(result.getContent(), topic, category, payload, length, result.getModel());
                    parsed.getRiskTips().addAll(0, riskTips);
                    if (parsed.isFallbackUsed()) {
                        attemptedErrors.add(model + "@" + hostOf(baseUrl) + ": output failed publishable contract");
                        continue;
                    }
                    if (!baseUrl.equals(config("ai.chat.baseUrl")) || !model.equals(requestedModel)) {
                        parsed.getRiskTips().add("Primary writer endpoint/model failed; generated with "
                                + model + " @ " + hostOf(baseUrl) + ".");
                    }
                    return parsed;
                } catch (Exception e) {
                    String msg = safeUpstreamMessage(e);
                    if (e.getStackTrace() != null && e.getStackTrace().length > 0) {
                        StackTraceElement top = e.getStackTrace()[0];
                        msg = msg + " @ " + top.getClassName() + "." + top.getMethodName()
                                + ":" + top.getLineNumber();
                    }
                    attemptedErrors.add(model + "@" + hostOf(baseUrl) + ": " + msg);
                    // Always log full stack for writer failures (journalctl).
                    e.printStackTrace(System.err);
                    if (isConnectivityFailure(msg)) {
                        sawConnectivityFailure = true;
                        // Same host is unreachable; skip remaining models on this baseUrl.
                        break;
                    }
                }
            }
        }

        if (!sawConnectivityFailure) {
            ContentGenerationResult chunked = generateArticleContentInChunks(
                    topic, category, payload, length, requestedModel, riskTips, attemptedErrors, evidenceBlock, planBlock);
            if (chunked != null) {
                return chunked;
            }
        } else {
            riskTips.add("AI 写稿上游连接失败（超时/不可达），已跳过分段重试以免长时间空转。主地址: "
                    + hostOf(config("ai.chat.baseUrl")) + "。");
        }
        riskTips.add("AI writer failed on all candidate models; local template fallback was used. Attempts: "
                + String.join(" | ", attemptedErrors));
        if (sawConnectivityFailure) {
            riskTips.add("local template fallback: AI upstream unreachable (connect timeout). "
                    + "Update ai.chat.baseUrl / ai.chat.apiKey (or content.writer.fallbackBaseUrls) then regenerate.");
        }
        return fallbackGeneration(topic, category, payload, length, requestedModel, riskTips);
    }

    private List<String> writerBaseUrlCandidates() {
        List<String> urls = new ArrayList<>();
        addUrlCandidate(urls, config("ai.chat.baseUrl"));
        String fallbacks = trimToNull(config("content.writer.fallbackBaseUrls"));
        if (fallbacks != null) {
            for (String item : fallbacks.split("[,\\n]")) {
                addUrlCandidate(urls, item);
            }
        }
        // Only hit official MiMo when a host-specific key is configured (relay key is often invalid there).
        if (hasText(trimToNull(config("content.writer.apiKey.api.xiaomimimo.com")))) {
            addUrlCandidate(urls, "https://api.xiaomimimo.com/v1");
        }
        return urls;
    }

    private void addUrlCandidate(List<String> urls, String value) {
        String url = trimToNull(value);
        if (url == null) {
            return;
        }
        url = url.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!urls.contains(url)) {
            urls.add(url);
        }
    }

    private String writerApiKeyForBase(String baseUrl) {
        String host = hostOf(baseUrl);
        // Optional per-host overrides: content.writer.apiKey.<host>
        String override = trimToNull(config("content.writer.apiKey." + host));
        if (override != null) {
            return override;
        }
        return config("ai.chat.apiKey");
    }

    private String hostOf(String baseUrl) {
        String value = defaultText(baseUrl, "");
        try {
            java.net.URI uri = java.net.URI.create(value.contains("://") ? value : "https://" + value);
            String host = uri.getHost();
            return hasText(host) ? host : value;
        } catch (Exception e) {
            return value.length() <= 40 ? value : value.substring(0, 40);
        }
    }

    private boolean isConnectivityFailure(String message) {
        String text = defaultText(message, "").toLowerCase(java.util.Locale.ROOT);
        return text.contains("timed out")
                || text.contains("timeout")
                || text.contains("connect")
                || text.contains("connection refused")
                || text.contains("network is unreachable")
                || text.contains("failed to connect")
                || text.contains("i/o failed");
    }


    private ContentGenerationResult generateArticleContentInChunks(String topic,
                                                                   String category,
                                                                   ContentArticleGenerateRequest payload,
                                                                   String length,
                                                                   String requestedModel,
                                                                   List<String> baseRiskTips,
                                                                   List<String> fullArticleErrors,
                                                                   String evidenceBlock,
                                                                   String planBlock) {
        List<String> models = writerModelCandidates(requestedModel);
        List<String> sectionErrors = new ArrayList<>();
        String title = buildChunkedTitle(topic);
        String digest = buildDigest(topic, payload.getAudience());
        List<String[]> sections = buildChunkSections(topic, payload, planBlock);

        List<String> markdown = new ArrayList<>();
        markdown.add("# " + title);
        markdown.add("");
        // Opening must read as a concrete scene (≥40 plain chars) for PublishableArticleContract.
        markdown.add("周五晚上 9 点，同事把周报甩进群里，你还在把 Excel 从第 3 页改到第 11 页。");
        markdown.add("同样都在用「" + topic + "」相关的 AI 工具，他 2 小时交差，你加班到地铁末班。");
        markdown.add("差距往往不在模型新旧，而在三个你每天都在做、却没改掉的习惯。");
        markdown.add("");
        markdown.add("> 真正贵的不是不会用 AI，而是用旧习惯把 AI 用成了更贵的加班。");
        markdown.add("");
        String usedModel = null;
        for (int i = 0; i < sections.size(); i++) {
            String[] sectionSpec = sections.get(i);
            if (i > 0) {
                markdown.add("## " + sectionSpec[0]);
                markdown.add("");
            }
            String section = generateOneChunk(models, topic, category, payload, sectionSpec[0], sectionSpec[1], evidenceBlock, sectionErrors);
            if (!hasText(section)) {
                return null;
            }
            if (usedModel == null && sectionErrors.size() < models.size()) {
                usedModel = models.get(Math.min(sectionErrors.size(), models.size() - 1));
            }
            markdown.add(section.trim());
            markdown.add("");
        }
        markdown.add("围绕「" + topic + "」，你觉得普通人最容易忽略的信息差是哪一层？欢迎在评论区聊聊。");
        markdown.add("觉得有用就点个关注，下次热点我继续第一时间拆清楚。");
        markdown.add("");

        List<String> risks = new ArrayList<>(baseRiskTips);
        risks.add("Full-article writer failed; article was assembled from shorter AI-written section calls. Full attempts: "
                + String.join(" | ", fullArticleErrors));
        if (!sectionErrors.isEmpty()) {
            risks.add("Section writer fallback attempts: " + String.join(" | ", sectionErrors));
        }
        return new ContentGenerationResult(
                title,
                digest,
                String.join("\n", markdown),
                buildCoverPrompt(topic, payload.getCoverStyle(), category),
                defaultTags(category),
                risks,
                defaultText(usedModel, requestedModel),
                false
        );
    }

    private List<String[]> buildChunkSections(String topic, ContentArticleGenerateRequest payload, String planBlock) {
        List<String[]> sections = new ArrayList<>();
        if (hasText(planBlock)) {
            for (String line : planBlock.split("\\r?\\n")) {
                String text = line == null ? "" : line.trim();
                if (text.startsWith("- ")) {
                    text = text.substring(2).trim();
                }
                if (!hasText(text) || text.startsWith("开头") || text.startsWith("核心判断") || text.startsWith("结尾")) {
                    continue;
                }
                String title = text.contains("|") ? text.split("\\|", 2)[0].trim() : text;
                if (title.length() > 28) {
                    title = title.substring(0, 28);
                }
                if (!hasText(title)) {
                    continue;
                }
                sections.add(new String[]{
                        title,
                        "围绕选题「" + topic + "」写 320-460 个中文字符。落实这一节意图：" + text
                                + "。必须包含具体场景/主体/后果之一，短段落，有判断，禁止通用模板句。"
                });
                if (sections.size() >= 5) {
                    break;
                }
            }
        }
        if (sections.size() >= 3) {
            return sections;
        }
        List<String[]> defaults = new ArrayList<>();
        defaults.add(new String[]{
                "先从最扎心的那一幕说起",
                "写 280-380 字：用「" + topic + "」相关的具体场景/对话/数字开场，120 字内亮出核心判断；口语、短句，像爆款推文。"
        });
        defaults.add(new String[]{
                "热闹背后，真正贵的是什么",
                "写 360-480 字：讲清「" + topic + "」里谁的时间/钱/机会被重新定价；点名至少一类具体角色，禁止空泛「信息差」。"
        });
        defaults.add(new String[]{
                "谁在赚钱，谁在买单",
                "写 360-480 字：拆利益链——平台/公司/从业者/普通人；用可观察后果，不要喊口号。"
        });
        defaults.add(new String[]{
                "最容易踩的那个坑",
                "写 320-450 字：指出围绕「" + topic + "」最常见的误读或跟风动作，并给 2 个立刻能做的核对动作。"
        });
        defaults.add(new String[]{
                "接下来怎么做更稳",
                "写 280-400 字：收束判断，给 2-3 个可执行下一步，最后留一个能引发留言的具体问题。"
        });
        return defaults;
    }

    private String generateOneChunk(List<String> models,
                                    String topic,
                                    String category,
                                    ContentArticleGenerateRequest payload,
                                    String heading,
                                    String requirement,
                                    String evidenceBlock,
                                    List<String> errors) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是微信增长向爆款写手。只输出这一节「读者会刷到、想继续往下看的正文段落」，不要标题、不要 JSON、不要提纲。\n");
        prompt.append("主题：").append(topic).append("\n");
        prompt.append("栏目：").append(category).append("\n");
        prompt.append("本节小标题：").append(heading).append("\n");
        prompt.append("本节任务：").append(requirement).append("\n");
        prompt.append("写法：口语短句、空行多、先场景/冲突再判断；让人觉得「说的就是我」，并想继续滑。\n");
        prompt.append("硬禁令：禁止「公众号该怎么写」「信息差方法论」「栏目定位」「本文将」「首先其次最后」「综上所述」；每一句都要落在「")
                .append(topic).append("」本身。\n");
        if (hasText(evidenceBlock)) {
            prompt.append("参考线索，只可用于背景，不要编造未出现的精确数据：\n")
                    .append(truncateForPrompt(evidenceBlock, 900)).append("\n");
        }
        for (String model : models) {
            try {
                AiChatUpstreamClient.ChatCompletionResult result = aiChatUpstreamClient.complete(
                        config("ai.chat.baseUrl"),
                        config("ai.chat.apiKey"),
                        model,
                        Arrays.asList(
                                new AiChatUpstreamClient.ChatMessage("system",
                                        "你是微信增长向爆款写手。直接输出中文正文，服务打开率与完读。禁止写作教学、栏目方法论和后台说明。"),
                                new AiChatUpstreamClient.ChatMessage("user", prompt.toString())
                        )
                );
                String content = trimToNull(result.getContent());
                if (content != null && content.length() >= 120 && !containsBackstageOutput(content)) {
                    return content;
                }
                errors.add(model + "/" + heading + ": output too short or backstage");
            } catch (Exception e) {
                errors.add(model + "/" + heading + ": " + safeUpstreamMessage(e));
            }
        }
        return null;
    }

    private String buildChunkedTitle(String topic) {
        return sharpenClickTitle(null, topic);
    }

    private String extractTitleFromMarkdown(String markdown, String fallback) {
        for (String line : defaultText(markdown, "").split("\\r?\\n")) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return defaultText(fallback, "早一步信息差");
    }

    /**
     * Make the outward-facing title click-worthy: emotion + curiosity, still gate-safe.
     * Prefer AI titles that already hook; only rewrite flat/news-copy titles.
     */
    private String sharpenClickTitle(String rawTitle, String topic) {
        String topicCore = compactTitleCore(topic, 16);
        String title = defaultText(rawTitle, "").trim()
                .replaceAll("^#+\\s*", "")
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", "");
        if (!hasText(title)) {
            return craftHookTitle(topicCore, topic);
        }
        for (String banned : TITLE_BANNED_PHRASES) {
            if (title.contains(banned)) {
                return craftHookTitle(topicCore, topic);
            }
        }
        String compactTopic = defaultText(topic, "").replaceAll("\\s+", "");
        boolean almostTopicOnly = title.equals(compactTopic)
                || (compactTopic.length() >= 8 && (title.equals(compactTopic + "？") || title.equals(compactTopic + "。")));
        boolean hasTension = title.contains("：") || title.contains("？") || title.contains("—")
                || title.contains("?") || title.contains(":");
        boolean hasEmotionOrCuriosity = containsAny(title,
                "别急", "先别", "别只", "别再", "真正", "到底", "谁在", "谁会", "心慌", "上头",
                "吃亏", "沉默", "坑", "贵", "亏", "为什么", "之后", "先停", "刷到", "看完",
                "最容易", "突然", "不灵", "站队", "转发", "加班", "钱包", "代价");
        if (almostTopicOnly || title.length() < 12 || (!hasTension && !hasEmotionOrCuriosity)) {
            return craftHookTitle(topicCore, topic);
        }
        if (title.length() > 36) {
            title = title.substring(0, 36);
        }
        // Short titles without punctuation still need a tension mark for the publish contract.
        if (title.length() < 14 && !hasTension) {
            return craftHookTitle(topicCore, topic);
        }
        return title;
    }

    private String craftHookTitle(String topicCore, String topicSeed) {
        String core = hasText(topicCore) ? topicCore : "这波热点";
        String seed = defaultText(topicSeed, core);
        int pick = Math.floorMod(seed.hashCode(), 6);
        // Keep core short enough that every template stays within mobile title limit.
        int maxCore = pick == 1 || pick == 3 ? 14 : 12;
        if (core.length() > maxCore) {
            core = core.substring(0, maxCore);
        }
        String title;
        switch (pick) {
            case 0:
                title = "刷到" + core + "先别站队：坑在这";
                break;
            case 1:
                title = core + "之后，很多人沉默了";
                break;
            case 2:
                title = "别急着转发：" + core + "真正改的是什么";
                break;
            case 3:
                title = core + "：你最容易亏在哪一步？";
                break;
            case 4:
                title = "看完" + core + "有点心慌：旧办法不灵了";
                break;
            default:
                title = "先别高兴：" + core + "谁在买单？";
                break;
        }
        if (title.length() > 36) {
            return title.substring(0, 36);
        }
        return title;
    }

    private String compactTitleCore(String value, int maxChars) {
        String text = defaultText(value, "热点").replaceAll("\\s+", "")
                .replaceAll("[《》【】\\[\\]\"“”‘’]", "");
        if (text.length() > maxChars) {
            return text.substring(0, maxChars);
        }
        return text;
    }

    private String sharpenDigest(String rawDigest, String topic, String title) {
        String digest = defaultText(rawDigest, "").trim().replaceAll("\\s+", " ");
        if (!isWeakDigest(digest, title)) {
            if (digest.length() > 120) {
                return digest.substring(0, 120);
            }
            return digest;
        }
        String core = compactTitleCore(topic, 14);
        return "刷到「" + core + "」时别急着表态：真正贵的往往不是热闹本身，而是你跟着做的那个动作。"
                + "谁会疼、谁会赚、你现在最容易踩的坑——点开看清半步。";
    }

    private boolean isWeakDigest(String digest, String title) {
        if (!hasText(digest) || digest.length() < 36) {
            return true;
        }
        String d = digest.trim();
        String t = defaultText(title, "").trim();
        if (hasText(t) && (d.equals(t) || d.startsWith(t))) {
            return true;
        }
        return d.contains("一文看懂") || d.contains("值得关注") || d.contains("深度解析");
    }

    private String truncateForPrompt(String value, int maxChars) {
        String text = defaultText(value, "");
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private boolean containsBackstageOutput(String value) {
        String text = defaultText(value, "");
        if (!hasText(text)) {
            return false;
        }
        // Planning residue + meta "how to write / how to run an account" tutorials.
        List<String> markers = Arrays.asList(
                "提纲", "素材清单", "可以这样写", "下面是", "本文将",
                "选题方案", "创作说明", "运营建议", "文章结构说明", "可选标题",
                "公众号真正要做", "口吻很重要", "热点解读不是", "信息差最容易出现",
                "判断一个热点值不值得写", "真正有用的热点解读", "这篇稿子真正想",
                "早一步信息差」要做", "早一步信息差」要做的", "栏目定位",
                "写作方法论", "教你如何写", "如何写好一篇", "内容创作技巧",
                "建议从以下角度", "改写如下", "仿写", "洗稿", "伪原创"
        );
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }


    private List<String> writerModelCandidates(String requestedModel) {
        List<String> models = new ArrayList<>();
        addModelCandidate(models, requestedModel);
        String configuredFallbacks = trimToNull(config("content.writer.fallbackModels"));
        if (configuredFallbacks != null) {
            for (String item : configuredFallbacks.split(",")) {
                addModelCandidate(models, item);
            }
        }
        // Prefer models actually listed by fufu relay; avoid dead ids that only burn time.
        addModelCandidate(models, "mimo-v2.5");
        addModelCandidate(models, "mimo-v2-flash");
        addModelCandidate(models, "mimo-v2.5-pro");
        return models;
    }

    private void addModelCandidate(List<String> models, String value) {
        String model = trimToNull(value);
        if (model != null && !models.contains(model)) {
            models.add(model);
        }
    }

    private String safeUpstreamMessage(String message) {
        String text = defaultText(message, "unknown").replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+\\-/=]+", "$1***");
        if (!hasText(text) || "null".equalsIgnoreCase(text.trim())) {
            text = "unknown";
        }
        return text.length() <= 180 ? text : text.substring(0, 180) + "...";
    }

    private String safeUpstreamMessage(Exception e) {
        if (e == null) {
            return "unknown";
        }
        String msg = e.getMessage();
        if (!hasText(msg)) {
            msg = e.getClass().getSimpleName();
            if (e.getCause() != null && hasText(e.getCause().getMessage())) {
                msg = msg + ": " + e.getCause().getMessage();
            }
        }
        return safeUpstreamMessage(msg);
    }

    private String buildArticlePrompt(String topic, String category, ContentArticleGenerateRequest payload, String length,
                                      String evidenceBlock, String planBlock) {
        StringBuilder prompt = new StringBuilder();
        ArticleLengthSpec lengthSpec = articleLengthSpec(length);
        // Growth-first WeChat article: open rate → finish rate → comment/share → follow.
        prompt.append("你是微信公众号「早一步信息差」的增长主笔。账号定位：把热点拆成普通人能立刻用的判断。\n");
        prompt.append("唯一业务目标：提高打开率、完读率、留言/转发，并自然带来关注。不是写作课，不是研报，不是信息差说明书。\n");
        prompt.append("选题（全文必须死死咬住）：").append(topic).append("\n");
        prompt.append("栏目：").append(category).append("\n");
        // Contract min is 1800 plain chars for standard; ask higher so whitespace/HTML variance still passes.
        int hardMin = Math.max(lengthSpec.getMinChars(), 1900);
        int hardTarget = Math.max(lengthSpec.getTargetChars(), 2300);
        prompt.append("篇幅：").append(lengthSpec.getLabel()).append("，正文纯中文至少 ").append(hardMin)
                .append(" 字（去掉空白后），目标约 ").append(hardTarget)
                .append(" 字；至少 14 个自然段、3-6 个 ## 小节。偏短=废稿。\n");

        prompt.append("\n【增长公式：打开 → 完读 → 互动 → 关注】\n");
        prompt.append("1) 打开率（标题 title + 摘要 digest）——这是第一KPI，标题废=全篇废\n");
        prompt.append("   - 标题 14-26 字（手机一行半内），目标：勾情绪 + 勾好奇，让人「不点就难受」。\n");
        prompt.append("   - 必须同时满足：①点到「").append(topic).append("」的具体点；②有冲突/代价/身份代入；③留半句悬念（别把答案写完）。\n");
        prompt.append("   - 优先公式（只选 1 种，不要堆辞藻；必须带 ： 或 ？ 或 — 之一）：\n");
        prompt.append("     · 身份+吃亏：「打工人先别高兴：……真正贵的是……」\n");
        prompt.append("     · 反转悬念：「都在夸……？真正坑在后半句」\n");
        prompt.append("     · 先别动作：「刷到……先别转发：三个人会吃亏」\n");
        prompt.append("     · 沉默好奇：「……之后，很多人沉默了」\n");
        prompt.append("     · 代价追问：「……：你最容易亏在哪一步？」\n");
        prompt.append("     · 情绪代入：「看完……有点心慌：旧办法突然不灵了」\n");
        prompt.append("   - 好情绪词优先（克制使用 1 个）：心慌、上头、吃亏、沉默、别急、真正、到底、谁在、坑、贵、亏。\n");
        prompt.append("   - 禁止：复述新闻原题、万能鸡汤、公文腔、写作课标题。\n");
        prompt.append("   - 禁词：一文看懂、一文读懂、深度解析、全面解读、和你有什么关系、背后的信息差、值得关注、你怎么看、该怎么看、震惊、炸裂、彻底变天、全网都、没人告诉你、再不知道就晚了、万万没想到。\n");
        prompt.append("   - digest（64-96 字，订阅号列表摘要）：第 1 句扎心场景或反常识；第 2 句只给半个判断+悬念；不要复述标题；让人想点进去看完。\n");

        prompt.append("2) 完读率（开头 3 秒 + 正文节奏）\n");
        prompt.append("   - 第 1 段：具体场景/对话/数字/反常识事实砸开「").append(topic).append("」，禁止「近年来」「随着」。\n");
        prompt.append("   - 第 2-3 段：立刻亮判断——谁会疼、谁会赚、旧办法哪里失效；让读者觉得「说的就是我」。\n");
        prompt.append("   - 每 2-4 段制造一次小钩子（反问、意外细节、半句悬念），推动往下划。\n");
        prompt.append("   - 一段 1-3 句，口语短句，像朋友语音讲明白；手机屏上要「好划」。\n");
        prompt.append("   - 节奏固定：扎心场景 → 拆穿一层 → 谁赚钱谁买单 → 最容易踩的坑 → 现在就能做的 2-3 步。\n");

        prompt.append("3) 转发/收藏（价值感）\n");
        prompt.append("   - 每节至少 1 个具体角色/岗位/公司类型/生活场景/数字区间；删掉选题名后不能通用。\n");
        prompt.append("   - 全文 1-2 句金句单独成段，口语、带刺、能截图转发；禁止排比堆砌和空洞鸡汤。\n");
        prompt.append("   - 给读者「能转发给同事/家人」的理由：少踩坑、少亏钱、早一步做对。\n");

        prompt.append("4) 留言 + 关注（转化，自然不硬广）\n");
        prompt.append("   - 文末必须有：一句收束判断 + 一个具体留言问题（关于本选题，能站队/吐槽/补充经历）。\n");
        prompt.append("   - 文末用 1 句轻关注引导，示例口吻：\n");
        prompt.append("     「觉得有用就点个关注，下次热点我继续第一时间拆清楚。」\n");
        prompt.append("     禁止乞讨式、禁止诱导分享、禁止「不关注就亏了」恐吓。\n");
        prompt.append("   - 不要在正文中段反复喊关注。\n");

        prompt.append("\n【小标题】像目录里会点开的人话，禁止「一、概述」「背景介绍」「总结与展望」。\n");
        if (hasText(evidenceBlock)) {
            prompt.append("\n证据线索（只可 grounding，用自己的话重组；禁止编造线索里没有的精确事实）：\n")
                    .append(evidenceBlock).append("\n");
        } else {
            prompt.append("\n证据偏薄：主张要保守，无把握就写成分析判断，禁止伪造链接和精确数据；正文不要写参考资料小节。\n");
        }
        if (hasText(planBlock)) {
            prompt.append("\n内部故事线（只帮你组织；禁止把计划字段、意图、配图提示原样写进正文）：\n")
                    .append(planBlock).append("\n");
        }
        prompt.append("\n【输出合同】\n");
        prompt.append("1. contentMarkdown 从 '# 标题' 开始，全文是读者正文。\n");
        prompt.append("2. 3-6 个 ## 小节；禁止 ## 参考资料 / 资料来源 / 延伸阅读 / References（证据只在后台，不进正文）。\n");
        prompt.append("3. contentHtml 与 Markdown 一致；正文侧用 # / ## / ###、> 引用、- 列表、**加粗**；勿写参考资料章节。\n");
        prompt.append("4. 禁止：公众号怎么写、信息差方法论、栏目定位、本文将、首先其次最后、综上所述、作为AI、洗稿腔。\n");
        prompt.append("5. 禁止通篇「规则/成本/选择空间」空话；换成这个选题里真实会碰到的动作和后果。\n");
        prompt.append("6. coverPrompt：适合订阅号头图的中文画面描述，强视觉冲突、少字、2.35:1 横图感；该字段只放 JSON，禁止把「提示词」「coverPrompt」「写作说明」写进 contentMarkdown。\n");
        prompt.append("7. contentMarkdown 纯读者正文：禁止出现 提示词、写作提纲、选题方案、运营建议、仿写、洗稿、参考资料列表。\n");
        prompt.append("\n自检（不通过就重写）：\n");
        prompt.append("A. 标题：朋友圈刷到会不会停一下？有没有情绪+好奇？是不是只剩新闻原题？\n");
        prompt.append("B. 摘要：不点开会不会觉得「少知道半句」？C. 前 100 字会不会停滑？\n");
        prompt.append("D. 删掉选题名后是否还成立（若成立=废稿）？E. 读完有没有理由留言/转给别人/点关注？\n");
        prompt.append("F. 像增长向爆款，还是像 AI 模板/研报？后者作废。\n");
        prompt.append("\n只返回 JSON：title,digest,contentMarkdown,contentHtml,coverPrompt,tags,riskTips。\n");
        prompt.append("其中 title 字段必须是最终对外标题（不是选题原句），与 contentMarkdown 的 # 标题完全一致。\n");
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
        String title = sharpenClickTitle(defaultText(asString(json.get("title")), topic), topic);
        String digest = sharpenDigest(
                defaultText(asString(json.get("digest")), buildDigest(topic, payload.getAudience())),
                topic,
                title);
        String markdown = firstText(json, "contentMarkdown", "markdown", "body", "article");
        if (!hasText(markdown)) {
            ContentGenerationResult fallback = fallbackGeneration(topic, category, payload, length, model, readStringList(json.get("riskTips")));
            fallback.getRiskTips().add("local template fallback: AI JSON did not include contentMarkdown.");
            return fallback;
        }
        markdown = ensureMarkdownTitle(markdown, title);
        String html = defaultText(firstText(json, "contentHtml", "html"), markdownToHtml(markdown));
        boolean publishable = isPublishableArticleDraft(markdown, html, length);
        String coverPrompt = defaultText(asString(json.get("coverPrompt")), buildCoverPrompt(topic, payload.getCoverStyle(), category));
        List<String> tags = new ArrayList<>(readStringList(json.get("tags")));
        if (tags.isEmpty()) {
            tags = new ArrayList<>(defaultTags(category));
        }
        List<String> risks = new ArrayList<>(readStringList(json.get("riskTips")));
        if (risks.isEmpty()) {
            risks = defaultRiskTips();
        }
        if (!publishable) {
            // Prefer keeping a salvageable AI draft for directed revision over a generic local template.
            if (isSalvageableAiDraft(markdown, html)) {
                risks.add("AI 成稿未完全达到发布契约，已保留原稿交由审稿/定向修订，而不是替换成本地模板。");
                return new ContentGenerationResult(title, digest, markdown, coverPrompt, tags, risks, model, false);
            }
            ContentGenerationResult fallback = fallbackGeneration(topic, category, payload, length, model, risks);
            fallback.getRiskTips().add("AI 返回内容过弱或含后台残留，已回退本地模板（不可发布）。");
            return fallback;
        }
        return new ContentGenerationResult(title, digest, markdown, coverPrompt, tags, risks, model, false);
    }

    /**
     * Keep imperfect AI prose when it still looks like a real article. Local templates are blocked by
     * the publish gate and historically produced "score-passing but unusable" drafts.
     */
    private boolean isSalvageableAiDraft(String markdown, String html) {
        String md = defaultText(markdown, "");
        if (!hasText(md) || containsBackstageOutput(md)) {
            return false;
        }
        String plain = htmlToPlainText(defaultText(html, markdownToHtml(md))).replaceAll("\\s+", "");
        if (plain.length() < 700) {
            return false;
        }
        int h2Count = countMatches(md, "## ") + countMatches(defaultText(html, "").toLowerCase(), "<h2");
        int paragraphs = countMatches(defaultText(html, "").toLowerCase(), "<p");
        if (paragraphs == 0) {
            paragraphs = md.split("\\n\\s*\\n").length;
        }
        return h2Count >= 2 && paragraphs >= 6;
    }

    private ContentGenerationResult fallbackGeneration(String topic,
                                                       String category,
                                                       ContentArticleGenerateRequest payload,
                                                       String length,
                                                       String model,
                                                       List<String> riskTips) {
        List<String> risks = new ArrayList<>(riskTips == null ? Collections.emptyList() : riskTips);
        if (risks.stream().noneMatch(item -> item != null && item.contains("local template fallback"))) {
            risks.add("local template fallback: AI writer output was missing, invalid or below the publishable contract.");
        }
        return new ContentGenerationResult(
                buildTitle(topic, category),
                buildDigest(topic, payload.getAudience()),
                buildMarkdown(topic, payload, length),
                buildCoverPrompt(topic, payload.getCoverStyle(), category),
                defaultTags(category),
                risks,
                model,
                true
        );
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

    private String buildTitle(String topic, String category) {
        return sharpenClickTitle(null, topic);
    }

    private String buildDigest(String topic, String audience) {
        return sharpenDigest(null, topic, sharpenClickTitle(null, topic));
    }

    /**
     * Emergency local body when AI fails. Topic-specific and readable, but publish gate still blocks fallback.
     * Prefer story-like mobile prose over "信息差方法论" scaffolding.
     */
    private String buildMarkdown(String topic, ContentArticleGenerateRequest payload, String length) {
        List<String> lines = new ArrayList<>();
        lines.add("# " + buildTitle(topic, DEFAULT_CATEGORY));
        lines.add("");
        lines.add("刷到「" + topic + "」的时候，很多人第一反应是转发、吐槽，或者急着表态。");
        lines.add("先停一下。");
        lines.add("更关键的问题只有三个：它具体改了什么？谁会立刻感到疼？你现在的默认做法，还灵不灵？");
        lines.add("");
        lines.add("> 这件事真正贵的，往往不是标题本身，而是你跟着标题做的那个动作。");
        lines.add("");
        lines.add("## 大家到底在吵什么");
        lines.add("把情绪滤掉，「" + topic + "」通常能拆成三层。");
        lines.add("第一层：已经发生、大家看得见的消息或动作。");
        lines.add("第二层：标题里没写清的约束——谁拍板、钱谁出、旧办法为什么突然不好使。");
        lines.add("第三层：落到你身上的体感——工作更忙了、花钱更贵了、机会入口变窄了，还是只是多了一波噪音。");
        lines.add("只盯第一层，很容易被带节奏；真正要看的是第二、三层有没有一起动。");
        lines.add("");
        lines.add("## 热闹背后，真正变贵的是什么");
        lines.add("落到「" + topic + "」上，别急着复述新闻，先问旧经验还灵不灵。");
        lines.add("以前管用的路径——多刷一点信息、早一点跟风、跟着主流走——在新约束下可能更贵、更慢、更被动。");
        lines.add("你可以先自问：");
        lines.add("");
        lines.add("- 规则改的是谁的？");
        lines.add("- 成本是上移到平台/机构，还是砸到个人头上？");
        lines.add("- 我现在的默认选择，是不是还建立在旧规则上？");
        lines.add("");
        lines.add("答得清这三个问题，比再刷十条短视频有用。");
        lines.add("");
        lines.add("## 谁在赚钱，谁在买单");
        lines.add("围绕「" + topic + "」，好处很少均匀分。");
        lines.add("手里有资源、渠道或解释权的一方，往往更快把变化翻译成优势；后知后觉的人，更容易在试错和补课里付费。");
        lines.add("从业者先碰到流程和指标，用户先碰到体验和价格，后来者可能发现入口已经被重新设计。");
        lines.add("所以别只问「这是不是好事」，先问：好消息进了谁口袋，账单又开给了谁。");
        lines.add("");
        lines.add("## 最容易踩的那个坑");
        lines.add("谈「" + topic + "」时，常见误判就两类。");
        lines.add("一类是把短期噪音当长期趋势：火了，不等于规则定死了。");
        lines.add("另一类是把别人的机会直接当成自己的：本金、时间、风险承受力都不一样，照抄很容易买在情绪最高点。");
        lines.add("更稳的做法是先找信号，再决定跟不跟：");
        lines.add("");
        lines.add("1. 官方或权威渠道有没有落到可执行细则；");
        lines.add("2. 相关方有没有用真金白银或组织调整表态；");
        lines.add("3. 你自己的工作/消费场景里，有没有出现不可逆的摩擦。");
        lines.add("");
        lines.add("只响一个，可以继续看；两个以上一起响，再认真重估自己的默认路径。");
        lines.add("");
        lines.add("## 写在最后");
        lines.add("「" + topic + "」会从热搜上消失，但它推动的调整可能还会在行业和日常里继续发酵。");
        lines.add("与其反复刷新评论区，不如先把事实、约束和自己的位置对齐。");
        lines.add("");
        lines.add("你觉得围绕「" + topic + "」，普通人最容易忽略的一层变化是什么？欢迎留言聊聊你的真实情况。");
        lines.add("觉得有用就点个关注，下次热点我继续第一时间拆清楚。");
        if ("long".equalsIgnoreCase(defaultText(length, "standard"))) {
            lines.add("");
            lines.add("如果后续细则、价格或组织动作出现，优先更新「谁受益/谁买单」这一段，而不是再堆情绪化标题。");
        }
        return lines.stream().collect(Collectors.joining("\n"));
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
                "规避检测",
                // Meta "how to write / how to run the account" tutorials (root cause of unusable drafts).
                "公众号真正要做",
                "口吻很重要",
                "真正有用的热点解读",
                "判断一个热点值不值得写",
                "信息差最容易出现的地方",
                "这篇稿子真正想",
                "「早一步信息差」要做",
                "早一步信息差」要做的",
                "写作方法论",
                "教你如何写",
                "如何写好一篇",
                "内容创作技巧",
                "栏目定位"
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
            return new ArticleLengthSpec("短稿", 1300, 1600);
        }
        if ("long".equals(value)) {
            return new ArticleLengthSpec("长稿", 2400, 3200);
        }
        // Align with PublishableArticleContract standard min (1800) / target (~2200).
        return new ArticleLengthSpec("标准稿", 1800, 2300);
    }

    private String buildCoverPrompt(String topic, String style, String category) {
        return "公众号封面图，主题：" + topic + "，栏目：" + category + "，风格：" + defaultText(style, "干净、清晰、有传播感");
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
        private boolean fallbackUsed;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class ArticleLengthSpec {
        private String label;
        private int minChars;
        private int targetChars;
    }
}
