package com.example.website.module.content.application;

import com.example.website.module.content.domain.ContentArticle;
import com.example.website.module.settings.application.SysConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.website.module.content.application.ContentTextUtils.defaultText;
import static com.example.website.module.content.application.ContentTextUtils.hasText;

/**
 * Orchestrates TrendPublish's quality pipeline around the existing writer. The pipeline is split
 * into two phases so it can wrap {@code ContentArticleService}'s prompt-based writing without a
 * circular dependency:
 *
 * <ol>
 *   <li>{@link #prepare} — pre-writing: 证据补全 (evidence) then 文章计划 (plan). Produces prompt
 *       blocks the writer injects, plus persistable artifacts and risk notes.</li>
 *   <li>{@link #finalize} — post-writing: 质量审稿 (review) then directed 修订 (revision).
 *       Returns the (possibly revised) markdown, the review artifact, and risk notes.</li>
 *   <li>{@link #refineAfterRender} — final HTML/visual pass after inline images are placed.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class ContentPipelineService {

    private static final String DEFAULT_IMAGE_MODE = "generate";

    private final SysConfigService configService;
    private final EvidenceService evidenceService;
    private final ArticlePlanService articlePlanService;
    private final ArticleReviewService articleReviewService;
    private final PublishableArticleContract publishableArticleContract;
    private final ContentAutomationBuilder automationBuilder;
    private final ObjectMapper objectMapper;

    public PreWriting prepare(String topic, String category, String angle, String audience) {
        return prepare(topic, category, angle, audience, true);
    }

    public PreWriting prepare(String topic, String category, String angle, String audience, boolean researchEnabled) {
        List<String> riskTips = new ArrayList<>();

        EvidenceService.EvidenceResult evidence;
        if (!researchEnabled) {
            evidence = EvidenceService.EvidenceResult.empty();
            riskTips.add("已关闭联网检索：本篇不抓取外部资料，事实边界需人工核验。");
        } else {
            evidence = evidenceService.collect(topic, angle, category);
            if (evidence.isEmpty() && evidenceEnabled() && searchConfigured()) {
                riskTips.add("联网检索未取回可用资料，本篇以观点分析为主，发布前请人工补充事实来源。");
            }
        }
        String evidenceBlock = evidence.toPromptBlock();

        ArticlePlanService.ArticlePlan plan = articlePlanService.plan(topic, category, angle, audience, evidenceBlock);

        return new PreWriting(evidenceBlock, plan.toPromptBlock(), plan, evidence.getSources(), riskTips);
    }

    /**
     * Post-writing phase: review the draft and apply up to {@link ArticleReviewService#maxRevisions()}
     * directed rewrites when below the publish bar.
     */
    public Reviewed finalize(String topic,
                             String category,
                             String markdown,
                             String length,
                             boolean hasEvidence,
                             boolean hasPlan,
                             boolean writerFallback) {
        return finalize(topic, category, markdown, length, hasEvidence, hasPlan, writerFallback, null, null);
    }

    public Reviewed finalize(String topic,
                             String category,
                             String markdown,
                             String length,
                             boolean hasEvidence,
                             boolean hasPlan,
                             boolean writerFallback,
                             String evidenceBlock,
                             String planBlock) {
        List<String> riskTips = new ArrayList<>();
        ArticleReviewService.Review review = articleReviewService.review(
                topic, category, markdown, evidenceBlock, planBlock);

        String finalMarkdown = markdown;
        int revisionCount = 0;
        int maxRevisions = articleReviewService.maxRevisions();
        while (review != null && review.needsRevision() && revisionCount < maxRevisions) {
            String rewritten = articleReviewService.reviseOnce(
                    topic, category, finalMarkdown, review, evidenceBlock, planBlock);
            if (!hasText(rewritten) || rewritten.equals(finalMarkdown)) {
                break;
            }
            finalMarkdown = rewritten;
            revisionCount++;
            ArticleReviewService.Review rescore = articleReviewService.review(
                    topic, category, finalMarkdown, evidenceBlock, planBlock);
            if (rescore != null) {
                review = rescore;
            } else {
                break;
            }
        }

        // evaluateDraft's first argument is the article title, not the topic.
        String title = extractTitle(finalMarkdown, topic);
        PublishableArticleContract.Evaluation gate = publishableArticleContract.evaluateDraft(
                title, finalMarkdown, null, length, hasEvidence, hasPlan, writerFallback);

        if (review == null) {
            review = new ArticleReviewService.Review(
                    gate.getScore(),
                    new ArrayList<>(gate.getIssues()),
                    "Deterministic publishability contract was used because AI review did not complete.",
                    PublishableArticleContract.MIN_PUBLISH_SCORE
            );
        } else if (gate.getScore() < review.getScore() || gate.blocksPublish()) {
            List<String> merged = new ArrayList<>(review.getIssues() == null ? new ArrayList<String>() : review.getIssues());
            for (String issue : gate.getIssues()) {
                if (hasText(issue) && !merged.contains(issue)) {
                    merged.add(issue);
                }
                if (merged.size() >= 8) {
                    break;
                }
            }
            review.setScore(Math.min(review.getScore(), gate.getScore()));
            review.setIssues(merged);
            review.setMinScore(PublishableArticleContract.MIN_PUBLISH_SCORE);
            review.setComment((review.getComment() == null ? "" : review.getComment() + " ")
                    + "Publishability contract decision=" + gate.getDecision() + ", score=" + gate.getScore() + ".");
        }
        if (review.needsRevision()) {
            riskTips.add("Final review score " + review.getScore() + " is below publish threshold " + review.getMinScore()
                    + "; " + (revisionCount > 0
                    ? revisionCount + " directed revision(s) applied but still below bar"
                    : "revision was skipped or unavailable")
                    + "; continue revising before publishing.");
        }
        if (gate.blocksPublish()) {
            riskTips.add("quality gate " + gate.getDecision() + ": " + String.join("; ", gate.getIssues()));
        }
        return new Reviewed(finalMarkdown, review, revisionCount > 0, revisionCount, riskTips, gate);
    }

    /**
     * After cover/inline assets and HTML render, re-score the exact candidate that would sync to WeChat.
     */
    public Reviewed refineAfterRender(Reviewed previous,
                                      String topic,
                                      String html,
                                      List<ArticleInlineImage> images) {
        if (previous == null) {
            return null;
        }
        ArticleReviewService.Review rendered = articleReviewService.reviewRenderedCandidate(
                previous.getReview(), topic, html, images);
        List<String> riskTips = new ArrayList<>(previous.getRiskTips() == null
                ? new ArrayList<String>()
                : previous.getRiskTips());
        if (rendered != null && rendered.needsRevision()) {
            riskTips.add("Rendered-candidate review score " + rendered.getScore()
                    + " is below publish threshold; fix HTML/visual placement before WeChat draft.");
        }
        PublishableArticleContract.Evaluation gate = previous.getPublishability();
        return new Reviewed(
                previous.getMarkdown(),
                rendered,
                previous.isRevised(),
                previous.getRevisionCount(),
                riskTips,
                gate
        );
    }

    boolean blocksWechatDraft(ContentArticle article) {
        if (article == null || hasGenerationFallback(article)) {
            return true;
        }
        PublishableArticleContract.Evaluation live = publishableArticleContract.evaluateArticle(article);
        if (live.blocksPublish()) {
            return true;
        }
        Integer stored = article.getQualityScore();
        int best = Math.max(stored == null ? 0 : stored, live.getScore());
        if (best >= PublishableArticleContract.MIN_PUBLISH_SCORE) {
            if (stored == null || stored < best) {
                article.setQualityScore(best);
            }
            return false;
        }
        return true;
    }

    PublishableArticleContract.Evaluation evaluateArticle(ContentArticle article) {
        return publishableArticleContract.evaluateArticle(article);
    }

    PublishableArticleContract.Evaluation evaluateGate(ContentArticle article,
                                                        String length,
                                                        String imageMode,
                                                        boolean writerFallback) {
        if (article == null) {
            return publishableArticleContract.evaluateDraft(null, null, null, length, false, false, writerFallback);
        }
        if ("none".equalsIgnoreCase(defaultText(imageMode, DEFAULT_IMAGE_MODE))) {
            PublishableArticleContract.Evaluation textGate = publishableArticleContract.evaluateDraft(
                    article.getTitle(),
                    article.getContentMarkdown(),
                    article.getContentHtml(),
                    defaultText(length, "standard"),
                    hasText(article.getEvidenceJson()),
                    hasText(article.getPlanJson()),
                    writerFallback || hasGenerationFallback(article)
            );
            textGate.getIssues().add("Text-only mode: cover/inline images skipped; add visuals before WeChat draft.");
            textGate.getMetrics().put("textOnly", true);
            textGate.getMetrics().put("hasCoverImage", false);
            return textGate;
        }
        return publishableArticleContract.evaluateArticle(article);
    }

    int reconcileQualityScore(int reviewScore,
                              int contractScore,
                              PublishableArticleContract.Evaluation gate) {
        int review = Math.max(0, Math.min(100, reviewScore));
        int contract = Math.max(0, Math.min(100, contractScore));
        if (gate != null && !gate.blocksPublish() && contract >= PublishableArticleContract.MIN_PUBLISH_SCORE) {
            return Math.max(review, contract);
        }
        return Math.min(review, contract);
    }

    void applyQualityState(ContentArticle article,
                           PublishableArticleContract.Evaluation gate,
                           boolean writerFallback) {
        boolean blocked = writerFallback || gate == null || gate.blocksPublish()
                || article.getQualityScore() == null
                || article.getQualityScore() < PublishableArticleContract.MIN_PUBLISH_SCORE;
        if (!blocked) {
            article.setErrorMessage(null);
            article.setAutomationJson(writeJson(automationBuilder.buildAutomation(article, null, "draft_ready")));
            return;
        }
        String message;
        if (writerFallback) {
            String risks = defaultText(article.getRiskTipsJson(), "");
            if (risks.contains("upstream unreachable") || risks.contains("连接失败") || risks.contains("Connect timed out")
                    || risks.contains("connect timeout") || risks.contains("timed out")) {
                message = "发布门禁未通过：AI 写稿上游连不上（当前 ai.chat.baseUrl 超时/不可达），"
                        + "本地模板不能推微信。请更新可用的对话 API 地址与密钥后重新生成。";
            } else {
                message = "发布门禁未通过：AI 正文失败后使用了本地模板，请重新生成后再推微信草稿。";
            }
        } else if (gate == null) {
            message = "发布门禁未通过：审稿未完成，请改稿或重新生成。";
        } else {
            List<String> issues = gate.getIssues() == null ? Collections.emptyList() : gate.getIssues();
            List<String> translatedIssues = new ArrayList<>();
            for (String issue : issues) {
                translatedIssues.add(toChineseGateIssue(issue));
                if (translatedIssues.size() >= 5) {
                    break;
                }
            }
            boolean textOnly = "none".equalsIgnoreCase(defaultText(article.getImageMode(), DEFAULT_IMAGE_MODE));
            boolean textReady = article.getQualityScore() != null
                    && article.getQualityScore() >= PublishableArticleContract.MIN_PUBLISH_SCORE
                    && !writerFallback;
            if (textOnly && textReady) {
                message = "正文已达发布分，但本次为无图模式：推微信草稿前请补封面与 2-3 张内联图（或改 imageMode 后重配图）。";
            } else {
                int shown = article.getQualityScore() == null ? gate.getScore() : article.getQualityScore();
                String detail = translatedIssues.isEmpty()
                        ? ("契约分=" + gate.getScore() + "，当前记录分=" + shown
                        + "。常见缺口：开场场景、H2 数量、封面或 2-3 张内联图未就绪（正文不要写参考资料）。")
                        : String.join("；", translatedIssues);
                message = "发布门禁未通过（" + gate.getDecision() + "，分=" + shown
                        + "，需≥" + PublishableArticleContract.MIN_PUBLISH_SCORE + "）：" + detail;
            }
        }
        article.setErrorMessage(message);
        article.setAutomationJson(writeJson(automationBuilder.buildAutomation(article, message, "quality_gate")));
    }

    boolean hasGenerationFallback(ContentArticle article) {
        String risks = article == null ? null : article.getRiskTipsJson();
        return hasText(risks) && (risks.contains("AI \u6b63\u6587\u751f\u6210\u5931\u8d25")
                || risks.contains("\u5df2\u56de\u9000\u672c\u5730\u6a21\u677f")
                || risks.contains("local template fallback"));
    }

    private String toChineseGateIssue(String issue) {
        String text = defaultText(issue, "").trim();
        if (!hasText(text)) {
            return "存在未说明问题";
        }
        if (text.contains("Text-only mode") || text.contains("textOnly") || text.contains("images skipped")) {
            return "无图模式：正文可先达标，推微信前再补封面与 2-3 张内联图";
        }
        if (text.contains("Missing cover") || text.contains("cover image")) {
            return "缺少可用封面图";
        }
        if (text.contains("inline image") || text.contains("Inline image")
                || text.contains("inline images") || text.contains("Inline images")) {
            return "内联配图不足或不可用（需 2-3 张）";
        }
        if (text.contains("local template") || text.contains("writer fallback") || text.contains("fallback")) {
            return "使用了本地模板回退，不能当正式成稿";
        }
        if (text.contains("Backstage") || text.contains("meta writing") || text.contains("planning residue")) {
            return "正文含写作说明/提纲等后台残留，需改成读者向成稿";
        }
        if (text.contains("too short") || text.contains("Body is too short")) {
            return "正文字数不足";
        }
        if (text.contains("H2") || text.contains("section")) {
            return "小节结构不完整";
        }
        if (text.contains("should not include a 参考资料") || text.contains("Body should not include")
                || text.contains("参考资料/来源小节") || text.contains("strip it for reader")) {
            return "正文不应包含参考资料/来源小节，请删掉";
        }
        if (text.contains("Missing references") || text.contains("reference section")) {
            return "正文无需参考资料小节（已取消该要求）";
        }
        if (text.contains("Title") || text.contains("title") || text.contains("Weak") || text.contains("Clickbait")) {
            return "标题偏弱或不合规：" + text;
        }
        if (text.contains("evidence") || text.contains("Evidence")) {
            return "证据包不足，事实需人工核验";
        }
        if (text.contains("template") || text.contains("reusable") || text.contains("methodology")) {
            return "正文像万能模板/写作方法论，缺少选题专属性";
        }
        return text;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "null";
        }
    }

    private String extractTitle(String markdown, String fallback) {
        for (String line : defaultTextLines(markdown)) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return fallback;
    }

    private List<String> defaultTextLines(String markdown) {
        List<String> lines = new ArrayList<>();
        if (!hasText(markdown)) {
            return lines;
        }
        for (String line : markdown.split("\\r?\\n")) {
            lines.add(line);
        }
        return lines;
    }

    private boolean evidenceEnabled() {
        return !"false".equalsIgnoreCase(configService.getValue(EvidenceService.CFG_ENABLED).orElse(""));
    }

    private boolean searchConfigured() {
        return hasText(configService.getValue(WebSearchService.CFG_API_KEY).orElse(""));
    }

    @Data
    public static class PreWriting {
        private final String evidenceBlock;
        private final String planBlock;
        private final ArticlePlanService.ArticlePlan plan;
        private final List<Map<String, Object>> evidenceSources;
        private final List<String> riskTips;

        public Map<String, Object> planAsMap() {
            if (plan == null || plan.isEmpty()) {
                return null;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("hook", plan.getHook());
            map.put("coreJudgment", plan.getCoreJudgment());
            map.put("sections", plan.getSections());
            map.put("callToAction", plan.getCallToAction());
            return map;
        }
    }

    @Data
    public static class Reviewed {
        private final String markdown;
        private final ArticleReviewService.Review review;
        private final boolean revised;
        private final int revisionCount;
        private final List<String> riskTips;
        private final PublishableArticleContract.Evaluation publishability;

        public Reviewed(String markdown,
                        ArticleReviewService.Review review,
                        boolean revised,
                        List<String> riskTips,
                        PublishableArticleContract.Evaluation publishability) {
            this(markdown, review, revised, revised ? 1 : 0, riskTips, publishability);
        }

        public Reviewed(String markdown,
                        ArticleReviewService.Review review,
                        boolean revised,
                        int revisionCount,
                        List<String> riskTips,
                        PublishableArticleContract.Evaluation publishability) {
            this.markdown = markdown;
            this.review = review;
            this.revised = revised;
            this.revisionCount = revisionCount;
            this.riskTips = riskTips;
            this.publishability = publishability;
        }

        public Map<String, Object> reviewAsMap() {
            if (review == null) {
                return null;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("score", review.getScore());
            map.put("minScore", review.getMinScore());
            map.put("issues", review.getIssues());
            map.put("fixActions", review.getFixActions());
            map.put("comment", review.getComment());
            map.put("revised", revised);
            map.put("revisionCount", revisionCount);
            if (publishability != null) {
                map.put("publishability", publishability);
            }
            return map;
        }

        public Integer qualityScore() {
            return review == null ? null : review.getScore();
        }
    }
}
