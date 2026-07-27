package com.example.website.service.content;

import com.example.website.entity.ContentArticle;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.website.service.content.ContentTextUtils.countMatches;
import static com.example.website.service.content.ContentTextUtils.defaultText;
import static com.example.website.service.content.ContentTextUtils.hasText;
import static com.example.website.service.content.ContentTextUtils.htmlToPlainText;

/** Deterministic publishability gate migrated from ai-trend-publish's quality-gate layer. */
@Component
@RequiredArgsConstructor
public class PublishableArticleContract {

    public static final int MIN_PUBLISH_SCORE = 88;

    private final ObjectMapper objectMapper;

    private static final List<String> BACKSTAGE_PHRASES = Arrays.asList(
            "选题方案", "写作提纲", "素材清单",
            "资料整理", "运营建议", "创作说明",
            "文章结构说明", "提示词", "可选标题",
            "下面是一篇", "下面这篇", "可以这样写",
            "建议从以下角度", "本文将从", "原文如下",
            "改写如下", "仿写", "洗稿", "伪原创", "规避检测",
            // Meta writing tutorials / account positioning essays (not publishable articles).
            "公众号真正要做", "口吻很重要", "真正有用的热点解读",
            "判断一个热点值不值得写", "信息差最容易出现的地方", "这篇稿子真正想",
            "「早一步信息差」要做", "早一步信息差」要做的", "写作方法论",
            "教你如何写", "如何写好一篇", "内容创作技巧", "栏目定位"
    );

    private static final List<String> WEAK_AI_PHRASES = Arrays.asList(
            "作为AI", "作为一个AI", "综上所述", "总而言之",
            "值得注意的是", "首先，其次，最后",
            "在当今社会", "随着时代的发展", "不难发现",
            "有着重要意义", "引发广泛关注", "众所周知"
    );

    private static final List<String> CLICKBAIT_PHRASES = Arrays.asList(
            "\u9707\u60ca", "\u70b8\u88c2", "\u5f7b\u5e95\u53d8\u5929", "\u5168\u7f51\u90fd",
            "\u6ca1\u4eba\u544a\u8bc9\u4f60", "\u518d\u4e0d\u77e5\u9053\u5c31\u665a\u4e86", "\u4e07\u4e07\u6ca1\u60f3\u5230"
    );

    private static final List<String> WEAK_TITLE_PHRASES = Arrays.asList(
            "\u548c\u4f60\u6709\u5565\u5173\u7cfb", "\u548c\u4f60\u6709\u4ec0\u4e48\u5173\u7cfb",
            "\u4f60\u600e\u4e48\u770b", "\u8be5\u600e\u4e48\u770b", "\u4e00\u6587\u770b\u61c2",
            "\u4e00\u6587\u8bfb\u61c2", "\u80cc\u540e\u7684\u4fe1\u606f\u5dee", "\u503c\u5f97\u5173\u6ce8"
    );

    public Evaluation evaluateArticle(ContentArticle article) {
        if (article == null) {
            return new Evaluation("block", 0, false, list("Article is missing."), new LinkedHashMap<>());
        }
        boolean writerFallback = hasText(article.getRiskTipsJson()) && (
                article.getRiskTipsJson().contains("AI \u6b63\u6587\u751f\u6210\u5931\u8d25")
                        || article.getRiskTipsJson().contains("\u5df2\u56de\u9000\u672c\u5730\u6a21\u677f")
                        || article.getRiskTipsJson().contains("local template fallback")
                        || article.getRiskTipsJson().contains("AI writer fallback")
        );
        Evaluation evaluation = evaluateDraft(
                article.getTitle(),
                article.getContentMarkdown(),
                article.getContentHtml(),
                "standard",
                hasText(article.getEvidenceJson()),
                hasText(article.getPlanJson()),
                writerFallback
        );
        if (!hasText(article.getCoverImageUrl())) {
            evaluation.getIssues().add("Missing cover image; publish-ready content must include a usable image.");
            evaluation.getMetrics().put("hasCoverImage", false);
            evaluation.setScore(Math.min(evaluation.getScore(), MIN_PUBLISH_SCORE - 10));
            evaluation.setDecision("revise");
            evaluation.setPublishable(false);
        } else {
            evaluation.getMetrics().put("hasCoverImage", true);
        }
        applyInlineImageContract(article, evaluation);
        return evaluation;
    }

    private void applyInlineImageContract(ContentArticle article, Evaluation evaluation) {
        List<ArticleInlineImage> images = readInlineImages(article.getInlineImagesJson());
        int ready = 0;
        java.util.Set<String> digests = new java.util.HashSet<>();
        boolean invalidAttribution = false;
        boolean unresolvedPlacement = false;
        for (ArticleInlineImage image : images) {
            if (image == null || !image.ready()) {
                continue;
            }
            ready++;
            if (!digests.add(image.getSha256())) {
                evaluation.getIssues().add("Duplicate inline image content detected: " + image.getSlotId());
            }
            if ("open_image".equals(image.getSourceType())
                    && (!hasText(image.getLicense()) || !hasText(image.getAttribution())
                    || !hasText(image.getSourcePageUrl()))) {
                invalidAttribution = true;
            }
            if (!htmlContainsImageUrl(article.getContentHtml(), image.renderUrl())) {
                unresolvedPlacement = true;
            }
        }
        evaluation.getMetrics().put("inlineImageCount", images.size());
        evaluation.getMetrics().put("readyInlineImageCount", ready);
        evaluation.getMetrics().put("distinctInlineImageCount", digests.size());
        evaluation.getMetrics().put("inlineImageAttributionValid", !invalidAttribution);
        evaluation.getMetrics().put("inlineImagePlacementValid", !unresolvedPlacement);
        if (ready < 2 || ready > 3) {
            evaluation.getIssues().add("Publish-ready standard articles require 2-3 validated inline images; ready=" + ready + ".");
        }
        if (digests.size() != ready) {
            evaluation.getIssues().add("Inline images must be distinct.");
        }
        if (invalidAttribution) {
            evaluation.getIssues().add("Open-image fallback is missing source, author or commercial-license attribution.");
        }
        if (unresolvedPlacement) {
            evaluation.getIssues().add("One or more inline images are not present in the final rendered HTML.");
        }
        if (ready < 2 || ready > 3 || digests.size() != ready || invalidAttribution || unresolvedPlacement) {
            evaluation.setScore(Math.min(evaluation.getScore(), MIN_PUBLISH_SCORE - 10));
            evaluation.setDecision("block");
            evaluation.setPublishable(false);
        }
    }

    private boolean htmlContainsImageUrl(String html, String url) {
        if (!hasText(html) || !hasText(url)) {
            return false;
        }
        return html.contains(url) || html.contains(ContentTextUtils.escape(url));
    }

    private List<ArticleInlineImage> readInlineImages(String json) {
        if (!hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ArticleInlineImage>>() {});
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    public Evaluation evaluateDraft(String title,
                                    String markdown,
                                    String html,
                                    String length,
                                    boolean hasEvidence,
                                    boolean hasPlan,
                                    boolean writerFallback) {
        List<String> issues = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        String md = defaultText(markdown, "");
        String rendered = hasText(html) ? html : md;
        String plain = htmlToPlainText(rendered).replaceAll("\\s+", "");
        int chars = plain.length();
        int minChars = minChars(length);
        int targetChars = targetChars(length);
        int h2 = hasText(html)
                ? countMatches(defaultText(html, "").toLowerCase(), "<h2")
                : countMatches(md, "## ");
        int paragraphs = Math.max(countMatches(defaultText(html, "").toLowerCase(), "<p"), md.split("\\n\\s*\\n").length);
        int blockquotes = countMatches(md, "> ") + countMatches(defaultText(html, "").toLowerCase(), "<blockquote");
        boolean hasReferenceSection = containsAny(md, "\u53c2\u8003\u8d44\u6599", "\u53c2\u8003\u6765\u6e90", "\u8d44\u6599\u6765\u6e90", "\u4fe1\u606f\u6765\u6e90", "\u5ef6\u4f38\u9605\u8bfb", "References", "Source");
        boolean hasInteraction = containsAny(md,
                "\u7559\u8a00", "\u8bc4\u8bba\u533a", "\u6b22\u8fce\u804a\u804a", "\u4f60\u89c9\u5f97", "\u4f60\u600e\u4e48\u770b",
                "\u6b22\u8fce\u5728\u8bc4\u8bba", "comment",
                "\u70b9\u4e2a\u5173\u6ce8", "\u70b9\u5173\u6ce8", "\u6b22\u8fce\u5173\u6ce8", "\u8ddf\u7740\u6211", "\u8ba2\u9605");
        boolean hasDigestLikeLead = firstNonTitleParagraph(md).length() >= 40;

        metrics.put("chars", chars);
        metrics.put("minChars", minChars);
        metrics.put("targetChars", targetChars);
        metrics.put("h2", h2);
        metrics.put("paragraphs", paragraphs);
        metrics.put("blockquotes", blockquotes);
        metrics.put("hasEvidence", hasEvidence);
        metrics.put("hasPlan", hasPlan);
        metrics.put("hasReferenceSection", hasReferenceSection);
        metrics.put("hasInteraction", hasInteraction);
        metrics.put("writerFallback", writerFallback);

        int score = 100;
        if (writerFallback) {
            score -= 45;
            issues.add("AI writer fallback/local template was used; regenerate instead of publishing this draft.");
        }
        if (!hasText(title) || title.trim().length() < 6) {
            score -= 12;
            issues.add("Title is missing or too short for publishing.");
        } else if (title.trim().length() > 36) {
            score -= 6;
            issues.add("Title is too long for mobile sharing.");
        }
        for (String phrase : CLICKBAIT_PHRASES) {
            if (hasText(title) && title.contains(phrase)) {
                score -= 10;
                issues.add("Clickbait title risk: " + phrase);
                break;
            }
        }

        for (String phrase : WEAK_TITLE_PHRASES) {
            if (hasText(title) && title.contains(phrase)) {
                score -= 12;
                issues.add("Weak/generic title phrase: " + phrase);
                break;
            }
        }
        if (hasText(title)
                && !title.contains("\uff1a")
                && !title.contains("\uff1f")
                && !title.contains("\u2014")
                && title.trim().length() < 14) {
            score -= 8;
            issues.add("Title lacks a clear tension point or information gap.");
        }
        if (chars < minChars) {
            score -= chars < minChars * 0.65 ? 30 : 18;
            issues.add("Body is too short: " + chars + " chars; minimum is " + minChars + ".");
        }
        if (h2 < 3) {
            score -= 15;
            issues.add("At least 3 informative H2 sections are required.");
        } else if (h2 > 7) {
            score -= 6;
            issues.add("Too many H2 sections; the main argument may be scattered.");
        }
        if (paragraphs < 14) {
            score -= 12;
            issues.add("Too few paragraphs for a complete mobile-reading article.");
        }
        if (!hasDigestLikeLead) {
            score -= 8;
            issues.add("Opening lacks a concrete scene or counter-intuitive judgment.");
        }
        // Product decision: WeChat body must NOT include a 参考资料 section (reader-facing prose only).
        // Evidence stays in backend fields for editors; do not score-penalize missing refs in markdown.
        if (hasReferenceSection) {
            score -= 8;
            issues.add("Body should not include a 参考资料/来源小节; keep reader prose only.");
        }
        if (!hasInteraction) {
            score -= 5;
            issues.add("Missing closing interaction prompt.");
        }
        if (!hasEvidence) {
            score -= 6;
            issues.add("No evidence pack; factual claims need manual verification before publishing.");
        }
        if (!hasPlan) {
            score -= 5;
            issues.add("No structured article plan; quality stability is weaker.");
        }
        for (String phrase : BACKSTAGE_PHRASES) {
            if (md.contains(phrase) || defaultText(html, "").contains(phrase)) {
                score -= 35;
                issues.add("Backstage prompt/planning residue found: " + phrase);
                break;
            }
        }
        for (String phrase : WEAK_AI_PHRASES) {
            if (md.contains(phrase)) {
                score -= 5;
                issues.add("Template-like AI expression should be rewritten with concrete scenes: " + phrase);
                break;
            }
        }
        if (containsAny(md, "\u6570\u636e\u663e\u793a", "\u7814\u7a76\u8868\u660e", "\u5b98\u65b9\u53d1\u5e03", "\u653f\u7b56\u660e\u786e", "\u62a5\u544a\u6307\u51fa") && !hasEvidence) {
            score -= 8;
            issues.add("Fact/data-style claims appear without evidence pack; tone down numbers or keep claims as analysis.");
        }
        String lowerHtml = defaultText(html, "").toLowerCase();
        if (lowerHtml.contains("<script") || lowerHtml.contains("<iframe")
                || lowerHtml.contains("javascript:") || lowerHtml.contains("[[image:")) {
            score -= 40;
            issues.add("Final HTML contains unsafe tags, URLs or unresolved image placeholders.");
        }
        if (issues.size() > 8) {
            issues = new ArrayList<>(issues.subList(0, 8));
        }
        score = Math.max(0, Math.min(100, score));
        String decision = score >= MIN_PUBLISH_SCORE ? "pass" : score >= 65 ? "revise" : "block";
        if (writerFallback || containsBackstage(md)) {
            decision = "block";
        }
        return new Evaluation(decision, score, "pass".equals(decision), issues, metrics);
    }

    private boolean containsBackstage(String md) {
        for (String phrase : BACKSTAGE_PHRASES) {
            if (md.contains(phrase)) return true;
        }
        return false;
    }

    private boolean containsAny(String text, String... needles) {
        if (!hasText(text)) return false;
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private String firstNonTitleParagraph(String markdown) {
        String[] parts = defaultText(markdown, "").split("\\n\\s*\\n");
        for (String part : parts) {
            String raw = defaultText(part, "").trim();
            // Skip pure heading blocks — H1 titles are often 12–30 chars and must not
            // be scored as the "opening scene" paragraph for WeChat articles.
            if (raw.matches("(?s)^#{1,6}\\s+\\S[^\\n]*$")) {
                continue;
            }
            String text = part.replaceAll("(?m)^#{1,6}\\s*", "")
                    .replaceAll("(?m)^>\\s*", "")
                    .replaceAll("\\s+", "")
                    .trim();
            if (text.length() >= 10) return text;
        }
        return "";
    }

    private int minChars(String length) {
        String value = defaultText(length, "standard").toLowerCase();
        if ("short".equals(value)) return 1300;
        if ("long".equals(value)) return 2400;
        return 1800;
    }

    private int targetChars(String length) {
        String value = defaultText(length, "standard").toLowerCase();
        if ("short".equals(value)) return 1600;
        if ("long".equals(value)) return 3200;
        return 2200;
    }

    private List<String> list(String item) {
        List<String> result = new ArrayList<>();
        result.add(item);
        return result;
    }

    @Data
    @AllArgsConstructor
    public static class Evaluation {
        private String decision;
        private int score;
        private boolean publishable;
        private List<String> issues;
        private Map<String, Object> metrics;

        public boolean blocksPublish() {
            return "block".equals(decision) || score < MIN_PUBLISH_SCORE || !publishable;
        }
    }
}
