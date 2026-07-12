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
 *   <li>{@link #finalize} — post-writing: 质量审稿 (review) then at-most-one 定向修订 (revision).
 *       Returns the (possibly revised) markdown, the review artifact, and risk notes.</li>
 * </ol>
 *
 * <p>Every stage is best-effort and independently switchable via {@code sys_config}; any failure
 * degrades to passing the draft through unchanged, matching the existing fallback philosophy.
 * This service is a leaf: it depends only on the individual stage services, never back on
 * {@code ContentArticleService}.
 */
@Service
@RequiredArgsConstructor
public class ContentPipelineService {

    private final SysConfigService configService;
    private final EvidenceService evidenceService;
    private final ArticlePlanService articlePlanService;
    private final ArticleReviewService articleReviewService;

    /**
     * Pre-writing phase: collect evidence for the topic, then plan the article against it.
     * The returned prompt blocks are injected into the writer prompt; the artifacts and risk
     * notes are carried through to persistence.
     */
    public PreWriting prepare(String topic, String category, String angle, String audience) {
        List<String> riskTips = new ArrayList<>();

        EvidenceService.EvidenceResult evidence = evidenceService.collect(topic, angle, category);
        String evidenceBlock = evidence.toPromptBlock();
        if (evidence.isEmpty() && evidenceEnabled() && searchConfigured()) {
            riskTips.add("联网检索未取回可用资料，本篇以观点分析为主，发布前请人工补充事实来源。");
        }

        ArticlePlanService.ArticlePlan plan = articlePlanService.plan(topic, category, angle, audience, evidenceBlock);

        return new PreWriting(evidenceBlock, plan.toPromptBlock(), plan, evidence.getSources(), riskTips);
    }

    /**
     * Post-writing phase: review the draft and, when it scores below the configured bar, run a
     * single targeted revision. Returns the final markdown (revised or original), the review
     * artifact, and any risk notes to append.
     */
    public Reviewed finalize(String topic, String category, String markdown) {
        List<String> riskTips = new ArrayList<>();
        ArticleReviewService.Review review = articleReviewService.review(topic, category, markdown);
        if (review == null) {
            return new Reviewed(markdown, null, false, riskTips);
        }

        String finalMarkdown = markdown;
        boolean revised = false;
        if (review.needsRevision() && articleReviewService.maxRevisions() >= 1) {
            String rewritten = articleReviewService.reviseOnce(topic, category, markdown, review);
            if (hasText(rewritten)) {
                finalMarkdown = rewritten;
                revised = true;
                // Re-score the revised draft so the persisted review reflects what actually ships.
                ArticleReviewService.Review rescore = articleReviewService.review(topic, category, finalMarkdown);
                if (rescore != null) {
                    review = rescore;
                }
            }
        }
        if (review.needsRevision()) {
            riskTips.add("终审评分 " + review.getScore() + " 未达标（阈值 " + review.getMinScore()
                    + "），已" + (revised ? "定向修订一次仍偏低" : "跳过修订") + "，建议发布前人工复核。");
        }
        return new Reviewed(finalMarkdown, review, revised, riskTips);
    }

    private boolean evidenceEnabled() {
        return !"false".equalsIgnoreCase(configService.getValue(EvidenceService.CFG_ENABLED).orElse(""));
    }

    private boolean searchConfigured() {
        return hasText(configService.getValue(WebSearchService.CFG_API_KEY).orElse(""));
    }

    /** Pre-writing artifacts: prompt blocks for the writer plus data to persist and surface. */
    @Data
    public static class PreWriting {
        private final String evidenceBlock;
        private final String planBlock;
        private final ArticlePlanService.ArticlePlan plan;
        private final List<Map<String, Object>> evidenceSources;
        private final List<String> riskTips;

        /** Plan as a JSON-friendly map for persistence; null when no plan was produced. */
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

    /** Post-writing outcome: the final markdown, the review, and whether a revision was applied. */
    @Data
    public static class Reviewed {
        private final String markdown;
        private final ArticleReviewService.Review review;
        private final boolean revised;
        private final List<String> riskTips;

        /** Review as a JSON-friendly map for persistence; null when review did not run. */
        public Map<String, Object> reviewAsMap() {
            if (review == null) {
                return null;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("score", review.getScore());
            map.put("minScore", review.getMinScore());
            map.put("issues", review.getIssues());
            map.put("comment", review.getComment());
            map.put("revised", revised);
            return map;
        }

        public Integer qualityScore() {
            return review == null ? null : review.getScore();
        }
    }
}
