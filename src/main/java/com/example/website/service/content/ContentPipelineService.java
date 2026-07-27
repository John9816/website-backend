package com.example.website.service.content;

import com.example.website.service.SysConfigService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.website.service.content.ContentTextUtils.hasText;

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

    private final SysConfigService configService;
    private final EvidenceService evidenceService;
    private final ArticlePlanService articlePlanService;
    private final ArticleReviewService articleReviewService;
    private final PublishableArticleContract publishableArticleContract;

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
