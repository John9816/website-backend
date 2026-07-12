package com.example.website.service.content;

import com.example.website.service.AiChatUpstreamClient;
import com.example.website.service.SysConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.example.website.service.content.ContentTextUtils.asString;
import static com.example.website.service.content.ContentTextUtils.defaultText;
import static com.example.website.service.content.ContentTextUtils.extractJsonObject;
import static com.example.website.service.content.ContentTextUtils.hasText;
import static com.example.website.service.content.ContentTextUtils.readStringList;
import static com.example.website.service.content.ContentTextUtils.trimToNull;

/**
 * Quality review + at-most-one targeted revision (质量审稿 + 定向修订), the TrendPublish step that
 * separates "the model produced text" from "the text is worth publishing". An AI reviewer scores
 * the draft across the dimensions this account cares about — factual grounding, depth of the
 * information-gap angle, conversational voice, structure, headline — and lists concrete issues.
 *
 * <p>When the score is below {@code content.review.minScore}, {@link #reviseOnce} rewrites the
 * draft against those issues exactly once (TrendPublish caps directed revision at one pass to
 * bound cost and avoid oscillation). Both steps are best-effort: a review failure yields a null
 * review and the caller keeps the original draft.
 */
@Service
@RequiredArgsConstructor
public class ArticleReviewService {

    public static final String CFG_ENABLED = "content.review.enabled";
    public static final String CFG_MIN_SCORE = "content.review.minScore";
    public static final String CFG_MAX_REVISIONS = "content.review.maxRevisions";

    private static final int DEFAULT_MIN_SCORE = 75;

    private final SysConfigService configService;
    private final AiChatUpstreamClient aiChatUpstreamClient;
    private final ObjectMapper objectMapper;

    /** Whether review is enabled; default on so quality gating applies unless turned off. */
    public boolean enabled() {
        return !"false".equalsIgnoreCase(config(CFG_ENABLED)) && hasText(config("ai.chat.baseUrl"));
    }

    /**
     * Score {@code markdown} for {@code topic}. Returns {@code null} when review is disabled,
     * unconfigured, or the model output is unusable — the caller then keeps the draft as-is.
     */
    public Review review(String topic, String category, String markdown) {
        if (!enabled() || !hasText(markdown)) {
            return null;
        }
        try {
            List<AiChatUpstreamClient.ChatMessage> messages = new ArrayList<>();
            messages.add(new AiChatUpstreamClient.ChatMessage("system",
                    "你是公众号「早一步信息差」的终审编辑，负责给待发布文章打分并指出问题。只输出 JSON，不要 Markdown 代码块。"));
            messages.add(new AiChatUpstreamClient.ChatMessage("user", buildReviewPrompt(topic, category, markdown)));
            AiChatUpstreamClient.ChatCompletionResult result = aiChatUpstreamClient.completeQuick(
                    config("ai.chat.baseUrl"),
                    config("ai.chat.apiKey"),
                    defaultText(config("ai.chat.defaultModel"), "mimo-v2.5-pro"),
                    messages
            );
            Map<String, Object> parsed = tryReadMap(extractJsonObject(result.getContent()));
            if (parsed == null) {
                return null;
            }
            int score = clampScore(parsed.get("score"));
            List<String> issues = readStringList(parsed.get("issues"));
            return new Review(score, issues, trimToNull(asString(parsed.get("comment"))), minScore());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Rewrite {@code markdown} against the review's issues, once. Uses the full writer client (not
     * the quick one) since this is a full-length rewrite. Returns {@code null} when the revision
     * fails or comes back too short, so the caller keeps the original draft.
     */
    public String reviseOnce(String topic, String category, String markdown, Review review) {
        if (review == null || !hasText(markdown) || !hasText(config("ai.chat.baseUrl"))) {
            return null;
        }
        try {
            List<AiChatUpstreamClient.ChatMessage> messages = new ArrayList<>();
            messages.add(new AiChatUpstreamClient.ChatMessage("system",
                    "你是公众号「早一步信息差」的资深主笔，负责按终审意见修订文章。只输出修订后的完整正文 Markdown，不要输出说明、点评或 JSON。"));
            messages.add(new AiChatUpstreamClient.ChatMessage("user", buildRevisionPrompt(topic, category, markdown, review)));
            AiChatUpstreamClient.ChatCompletionResult result = aiChatUpstreamClient.complete(
                    config("ai.chat.baseUrl"),
                    config("ai.chat.apiKey"),
                    defaultText(config("ai.chat.defaultModel"), "mimo-v2.5-pro"),
                    messages
            );
            String revised = trimToNull(stripCodeFence(result.getContent()));
            // Guard against a truncated/stub rewrite: only accept a revision of comparable length.
            if (revised == null || revised.length() < Math.min(markdown.length() / 2, 400)) {
                return null;
            }
            return revised;
        } catch (Exception e) {
            return null;
        }
    }

    /** Max directed revision passes; hard-capped at 1 per TrendPublish's design. */
    public int maxRevisions() {
        return 1;
    }

    private String buildReviewPrompt(String topic, String category, String markdown) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请审阅下面这篇公众号待发布文章，并按标准打分。\n");
        prompt.append("选题：").append(topic).append("\n");
        prompt.append("栏目：").append(category).append("\n");
        prompt.append("评分维度（各占权重，综合成 0-100 的总分）：\n");
        prompt.append("  1. 事实与证据：有没有硬编事实、数据、人物经历；资料不足时是否降级为观点。\n");
        prompt.append("  2. 信息差深度：是否讲出了普通人容易忽略的规则/成本/选择，而不是复述新闻。\n");
        prompt.append("  3. 口语化表达：像不像有判断的朋友在讲；是否避免了 AI 腔、研报腔和“首先/其次/综上所述”。\n");
        prompt.append("  4. 结构与可读性：开头是否抓人；小节是否都回答“和读者有什么关系”；手机阅读段落是否够短。\n");
        prompt.append("  5. 标题吸引力：标题是否有信息量、有传播感，且不做标题党。\n");
        prompt.append("\n待审文章（Markdown）：\n").append(markdown).append("\n");
        prompt.append("\n请严格输出 JSON，字段：\n");
        prompt.append("  score（0-100 整数总分）,\n");
        prompt.append("  issues（字符串数组，列出最该改的具体问题，最多 5 条；没有问题给空数组）,\n");
        prompt.append("  comment（一句话总体评价）。");
        return prompt.toString();
    }

    private String buildRevisionPrompt(String topic, String category, String markdown, Review review) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请根据终审意见修订下面这篇公众号文章，保持选题和主要内容不变，重点解决被指出的问题。\n");
        prompt.append("选题：").append(topic).append("\n");
        prompt.append("栏目：").append(category).append("\n");
        if (review.getIssues() != null && !review.getIssues().isEmpty()) {
            prompt.append("需要解决的问题：\n");
            for (String issue : review.getIssues()) {
                if (hasText(issue)) {
                    prompt.append("- ").append(issue.trim()).append("\n");
                }
            }
        }
        if (hasText(review.getComment())) {
            prompt.append("终审总体评价：").append(review.getComment().trim()).append("\n");
        }
        prompt.append("\n修订要求：不要编造事实；保持口语化、有判断、拒绝 AI 腔；");
        prompt.append("保留 #/## 标题、自然段、引用和列表格式；从文章标题开始输出完整正文，不要任何前后说明。\n");
        prompt.append("\n原文如下：\n").append(markdown);
        return prompt.toString();
    }

    private String stripCodeFence(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        return text;
    }

    private int minScore() {
        return DEFAULT_MIN_SCORE;
    }

    private int clampScore(Object value) {
        if (value instanceof Number) {
            return clamp(((Number) value).intValue(), 0, 100);
        }
        return clamp(parseInt(asString(value), 0), 0, 100);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int parseInt(String value, int fallback) {
        String text = trimToNull(value);
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
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

    private String config(String key) {
        return configService.getValue(key).orElse("");
    }

    /**
     * A review verdict. {@link #needsRevision()} drives the one-shot revision decision; the raw
     * fields are persisted so the score and issues show up in the run for review.
     */
    @Data
    @AllArgsConstructor
    public static class Review {
        private int score;
        private List<String> issues;
        private String comment;
        private int minScore;

        public boolean needsRevision() {
            return score < minScore;
        }
    }
}
