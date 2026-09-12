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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.example.website.service.content.ContentTextUtils.asString;
import static com.example.website.service.content.ContentTextUtils.countMatches;
import static com.example.website.service.content.ContentTextUtils.defaultText;
import static com.example.website.service.content.ContentTextUtils.extractJsonObject;
import static com.example.website.service.content.ContentTextUtils.hasText;
import static com.example.website.service.content.ContentTextUtils.readStringList;
import static com.example.website.service.content.ContentTextUtils.trimToNull;

/**
 * Quality review + directed revision.
 *
 * <p>Revision is triggered when the draft is below the publication bar (default aligned with
 * {@link PublishableArticleContract#MIN_PUBLISH_SCORE}), not only a soft editorial threshold.
 */
@Service
@RequiredArgsConstructor
public class ArticleReviewService {

    public static final String CFG_ENABLED = "content.review.enabled";
    public static final String CFG_MIN_SCORE = "content.review.minScore";
    public static final String CFG_MAX_REVISIONS = "content.review.maxRevisions";

    /** Align with the deterministic publish gate so 75-87 drafts still get a directed rewrite. */
    private static final int DEFAULT_MIN_SCORE = PublishableArticleContract.MIN_PUBLISH_SCORE;

    private static final List<String> BACKSTAGE_PHRASES = Arrays.asList(
            "选题方案", "写作提纲", "素材清单", "资料整理", "运营建议", "创作说明",
            "文章结构说明", "提示词", "可选标题", "下面是一篇", "下面这篇", "可以这样写",
            "建议从以下角度", "本文将从", "原文如下", "改写如下", "仿写", "洗稿", "伪原创", "规避检测",
            "公众号真正要做", "口吻很重要", "真正有用的热点解读", "判断一个热点值不值得写",
            "信息差最容易出现的地方", "这篇稿子真正想", "「早一步信息差」要做", "早一步信息差」要做的",
            "写作方法论", "教你如何写", "如何写好一篇", "内容创作技巧", "栏目定位"
    );

    private static final List<String> WEAK_AI_PHRASES = Arrays.asList(
            "作为AI", "作为一个AI", "综上所述", "总而言之", "值得注意的是",
            "首先，其次，最后", "在当今社会", "随着时代的发展", "不难发现", "有着重要意义",
            "引发广泛关注", "无疑", "众所周知"
    );

    private static final List<String> WEAK_TITLE_PHRASES = Arrays.asList(
            "和你有啥关系", "和你有什么关系", "你怎么看", "该怎么看",
            "一文看懂", "一文读懂", "背后的信息差", "值得关注"
    );

    private final SysConfigService configService;
    private final AiChatUpstreamClient aiChatUpstreamClient;
    private final ObjectMapper objectMapper;

    public boolean enabled() {
        return !"false".equalsIgnoreCase(config(CFG_ENABLED));
    }

    public Review review(String topic, String category, String markdown) {
        return review(topic, category, markdown, null, null);
    }

    public Review review(String topic,
                         String category,
                         String markdown,
                         String evidenceBlock,
                         String planBlock) {
        if (!enabled() || !hasText(markdown)) {
            return null;
        }
        if (!hasText(config("ai.chat.baseUrl"))) {
            return localReview(topic, markdown, "AI review is not configured; local editorial review was used.");
        }
        try {
            List<AiChatUpstreamClient.ChatMessage> messages = new ArrayList<>();
            messages.add(new AiChatUpstreamClient.ChatMessage("system",
                    "你是微信公众号终审主编。只根据可发布标准打分，返回严格 JSON，不要 Markdown 围栏。"));
            messages.add(new AiChatUpstreamClient.ChatMessage("user",
                    buildReviewPrompt(topic, category, markdown, evidenceBlock, planBlock)));
            AiChatUpstreamClient.ChatCompletionResult result = aiChatUpstreamClient.completeQuick(
                    config("ai.chat.baseUrl"),
                    config("ai.chat.apiKey"),
                    defaultText(config("ai.chat.defaultModel"), "mimo-v2.5-pro"),
                    messages
            );
            Map<String, Object> parsed = tryReadMap(extractJsonObject(result.getContent()));
            if (parsed == null) {
                return localReview(topic, markdown, "AI review returned unparsable output; local editorial review was used.");
            }
            int score = clampScore(parsed.get("score"));
            List<String> issues = readStringList(parsed.get("issues"));
            List<String> fixActions = readStringList(firstPresent(parsed, "fixActions", "actions", "revisionDirectives"));
            Review local = localReview(topic, markdown, null);
            // AI reviewers tend to overscore; cap optimism against local structure/editorial heuristics.
            if (score > local.getScore() + 8) {
                score = local.getScore() + 8;
                issues = mergeIssues(issues, local.getIssues());
            }
            if (local.getScore() < minScore() && score >= minScore()) {
                score = Math.min(score, local.getScore() + 5);
                issues = mergeIssues(issues, local.getIssues());
            }
            String comment = trimToNull(asString(parsed.get("comment")));
            if (!hasText(comment)) {
                comment = local.getComment();
            }
            return new Review(score, issues, comment, minScore(), fixActions);
        } catch (Exception e) {
            return localReview(topic, markdown, "AI review failed; local editorial review was used: " + safeMessage(e.getMessage()));
        }
    }

    /**
     * Lightweight pass on the final HTML that will be sent toward WeChat.
     * Does not rewrite body text; only adjusts score/issues for render defects.
     */
    public Review reviewRenderedCandidate(Review previous, String topic, String html, List<ArticleInlineImage> images) {
        Review base = previous == null
                ? new Review(0, new ArrayList<String>(), "Rendered review without prior Markdown review.", minScore(), new ArrayList<String>())
                : previous;
        List<String> issues = new ArrayList<>(base.getIssues() == null ? new ArrayList<String>() : base.getIssues());
        List<String> fixActions = new ArrayList<>(base.getFixActions() == null ? new ArrayList<String>() : base.getFixActions());
        int score = base.getScore();
        String lower = defaultText(html, "").toLowerCase();
        if (!hasText(html)) {
            score = Math.min(score, 40);
            addIssue(issues, "Final HTML is empty; cannot publish without rendered body.");
            addIssue(fixActions, "Re-render Markdown to WeChat HTML after fixing the body.");
        }
        if (lower.contains("<script") || lower.contains("<iframe") || lower.contains("javascript:") || lower.contains("[[image:")) {
            score = Math.min(score, 30);
            addIssue(issues, "Final HTML contains unsafe tags or unresolved image placeholders.");
            addIssue(fixActions, "Remove unsupported tags and unresolved image placeholders from the rendered HTML.");
        }
        int h2 = countMatches(lower, "<h2");
        if (h2 < 3) {
            score = Math.min(score, score - 10);
            addIssue(issues, "Rendered HTML has fewer than 3 H2 sections.");
            addIssue(fixActions, "Ensure the Markdown has 3-6 concrete ## sections before rendering.");
        }
        int readyImages = 0;
        if (images != null) {
            for (ArticleInlineImage image : images) {
                if (image != null && image.ready()) {
                    readyImages++;
                    String url = image.renderUrl();
                    if (hasText(url) && !html.contains(url) && !html.contains(ContentTextUtils.escape(url))) {
                        score = Math.min(score, score - 8);
                        addIssue(issues, "Inline image not placed in rendered HTML: " + image.getSlotId());
                        addIssue(fixActions, "Re-render with section-title matched inline images.");
                    }
                }
            }
        }
        if (readyImages < 2) {
            score = Math.min(score, PublishableArticleContract.MIN_PUBLISH_SCORE - 10);
            addIssue(issues, "Rendered candidate has fewer than 2 ready inline images.");
        }
        if (!hasText(topic) || !defaultText(html, "").contains(topic.substring(0, Math.min(4, topic.length())))) {
            // soft check only when topic is long enough to be meaningful
        }
        score = clamp(score, 0, 100);
        String comment = base.getComment();
        if (score < minScore()) {
            comment = (comment == null ? "" : comment + " ")
                    + "Rendered-candidate review found publication blockers in the final HTML.";
        }
        return new Review(score, trimIssues(issues, 8), trimToNull(comment), minScore(), trimIssues(fixActions, 6));
    }

    public String reviseOnce(String topic, String category, String markdown, Review review) {
        return reviseOnce(topic, category, markdown, review, null, null);
    }

    public String reviseOnce(String topic,
                             String category,
                             String markdown,
                             Review review,
                             String evidenceBlock,
                             String planBlock) {
        if (review == null || !hasText(markdown) || !hasText(config("ai.chat.baseUrl"))) {
            return null;
        }
        try {
            List<AiChatUpstreamClient.ChatMessage> messages = new ArrayList<>();
            messages.add(new AiChatUpstreamClient.ChatMessage("system",
                    "你是微信公众号资深改稿编辑。只输出完整修订后的 Markdown 正文（从 # 标题到结尾），不要 JSON、不要解释、不要提纲。"));
            messages.add(new AiChatUpstreamClient.ChatMessage("user",
                    buildRevisionPrompt(topic, category, markdown, review, evidenceBlock, planBlock)));
            AiChatUpstreamClient.ChatCompletionResult result = aiChatUpstreamClient.complete(
                    config("ai.chat.baseUrl"),
                    config("ai.chat.apiKey"),
                    defaultText(config("ai.chat.defaultModel"), "mimo-v2.5-pro"),
                    messages
            );
            String revised = trimToNull(stripCodeFence(result.getContent()));
            if (revised == null || revised.length() < Math.min(markdown.length() / 2, 400)) {
                return null;
            }
            if (containsBackstage(revised)) {
                return null;
            }
            return revised;
        } catch (Exception e) {
            return null;
        }
    }

    public int maxRevisions() {
        int configured = parseInt(config(CFG_MAX_REVISIONS), 1);
        return Math.max(0, Math.min(2, configured));
    }

    private Review localReview(String topic, String markdown, String note) {
        List<String> issues = new ArrayList<>();
        List<String> fixActions = new ArrayList<>();
        int score = 100;
        String normalized = defaultText(markdown, "");
        String plain = normalized.replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", "").trim();

        String titleLine = firstTitle(normalized);
        if (!hasText(titleLine) || titleLine.length() < 8) {
            score -= 12;
            issues.add("Title is missing or too short for publishing.");
            fixActions.add("Rewrite the H1 title to 12-26 Chinese characters with a concrete conflict or decision cost.");
        } else {
            for (String phrase : WEAK_TITLE_PHRASES) {
                if (titleLine.contains(phrase)) {
                    score -= 12;
                    issues.add("Weak/generic title phrase: " + phrase);
                    fixActions.add("Replace the weak title ending with a specific information-gap or consequence.");
                    break;
                }
            }
            if (titleLine.length() < 12 && !titleLine.contains("：") && !titleLine.contains("？") && !titleLine.contains("—")) {
                score -= 8;
                issues.add("Title lacks a clear tension point or information gap.");
                fixActions.add("Add a colon/contrast in the title that names the real tradeoff.");
            }
        }

        int chars = plain.length();
        if (chars < 900) {
            score -= 25;
            issues.add("Body is too short for a publish-ready analysis article.");
            fixActions.add("Expand each major section with a concrete scene, stakeholder and consequence.");
        } else if (chars < 1500) {
            score -= 12;
            issues.add("Body is slightly short; add examples, impact and judgment chain.");
            fixActions.add("Add topic-specific examples so sections cannot be reused for another news item.");
        } else if (chars < 1800) {
            score -= 6;
            issues.add("Body is under the standard publish length bar.");
        }

        int h2 = countMatches(normalized, "## ");
        if (h2 < 3) {
            score -= 15;
            issues.add("Fewer than 3 section headings; structure is incomplete.");
            fixActions.add("Rebuild into 3-6 ## sections following one argument chain.");
        } else if (h2 > 7) {
            score -= 6;
            issues.add("Too many H2 sections; the main argument may be scattered.");
            fixActions.add("Merge weak sections so the thesis stays one chain.");
        }

        int paragraphs = normalized.split("\\n\\s*\\n").length;
        if (paragraphs < 10) {
            score -= 10;
            issues.add("Too few paragraphs for mobile reading rhythm.");
            fixActions.add("Break long blocks into short mobile paragraphs of 1-3 sentences.");
        }

        for (String pattern : BACKSTAGE_PHRASES) {
            if (normalized.contains(pattern)) {
                score -= 35;
                issues.add("Draft contains backstage/meta writing residue: " + pattern);
                fixActions.add("Rewrite as a topic-specific article for readers; delete planning residue and writing-class meta narration.");
                break;
            }
        }
        // Heuristic: account/method essays that teach "how to interpret hot topics" instead of covering the topic.
        boolean metaVoice = containsAny(normalized, "公众号", "栏目", "口吻", "写作", "热点解读")
                && containsAny(normalized, "要做的", "真正要做", "值不值得写", "方法论", "如何写");
        if (metaVoice) {
            score = Math.min(score, 55);
            issues.add("Draft reads like writing/account methodology instead of a real article about the selected topic.");
            fixActions.add("Throw away meta sections; rewrite every H2 around facts, stakeholders and consequences of the selected topic.");
        }
        for (String phrase : WEAK_AI_PHRASES) {
            if (normalized.contains(phrase)) {
                score -= 6;
                issues.add("Template-like AI expression should be rewritten with concrete scenes: " + phrase);
                fixActions.add("Replace summary-like AI phrases with topic-specific judgments.");
                break;
            }
        }

        if (containsAny(normalized, "参考资料", "参考来源", "资料来源", "信息来源", "延伸阅读")
                || normalized.toLowerCase().contains("references")) {
            score -= 8;
            issues.add("Body contains a 参考资料/来源 section; strip it for reader-facing WeChat prose.");
            fixActions.add("Delete the ## 参考资料 (or equivalent) section entirely; keep only reader narrative and a closing question.");
        }
        if (!containsAny(normalized, "留言", "评论区", "欢迎聊聊", "你觉得", "你怎么看")) {
            score -= 5;
            issues.add("Missing a clear closing interaction prompt.");
            fixActions.add("End with one concrete discussion question for the comment section.");
        }
        if (containsAny(normalized, "首先", "其次", "最后", "综上所述")
                && !containsAny(normalized, "我更关心", "真正", "别急", "先别")) {
            score -= 5;
            issues.add("Tone is too summary-like; add conversational judgment and scenes.");
            fixActions.add("Rewrite transitions in a conversational voice with one counter-intuitive judgment.");
        }

        // Topic-specificity: removing the topic should not leave a fully generic shell.
        if (hasText(topic)) {
            String topicKey = topic.replaceAll("\\s+", "");
            String sample = topicKey.length() <= 6 ? topicKey : topicKey.substring(0, 6);
            int topicHits = countMatches(normalized, sample);
            if (topicHits < 2 && topicKey.length() >= 4) {
                score -= 12;
                issues.add("Body is not tightly grounded in the selected topic; it reads like a reusable template.");
                fixActions.add("Bind every major section to the selected topic with at least one concrete fact, name, product or rule.");
            }
        }

        if (hasText(note)) {
            issues.add(note);
        }
        score = clamp(score, 0, 100);
        String comment = score >= minScore()
                ? "Local editorial review passed structural checks; still verify facts and title sharpness before publishing."
                : "Local editorial review failed; revise title, topic specificity, sources or completeness before publishing.";
        return new Review(score, trimIssues(issues, 8), comment, minScore(), trimIssues(fixActions, 6));
    }

    private String buildReviewPrompt(String topic,
                                     String category,
                                     String markdown,
                                     String evidenceBlock,
                                     String planBlock) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请以「能涨阅读、能促关注的微信爆款」为标准审这篇稿，按 0-100 打分。\n");
        prompt.append("业务目标：打开率、完读率、留言/转发、自然关注。不是写作课，不是 AI 万能模板。\n");
        prompt.append("选题：").append(topic).append("\n栏目：").append(category).append("\n");
        prompt.append("最低可发布分：").append(minScore()).append("。低于该分必须给出可执行修订指令。\n");
        prompt.append("\n评分维度：\n");
        prompt.append("1. 打开：标题 14-26 字，必须同时勾情绪+好奇（身份/代价/反转/悬念），带 ：？— 之一；")
                .append("像朋友圈会停一下的标题，不是新闻原题复述；禁弱标题与恐吓式震惊体。\n");
        prompt.append("2. 完读：开头 3 秒有场景/数字/对话，前 120 字亮判断；中段有小钩子推动下滑。\n");
        prompt.append("3. 价值：场景→拆穿→受益/买单→坑→可执行下一步；有可转发金句或清单。\n");
        prompt.append("4. 专属：删掉选题名后不能通用；每节有具体场景/角色/后果。\n");
        prompt.append("5. 事实：不编造数据/政策/公司动作；无把握就降级为分析判断，禁止在正文堆「参考资料」小节。\n");
        prompt.append("6. 转化：文末有具体留言问题 + 一句轻关注引导（不硬广、不诱导分享）。\n");
        prompt.append("7. 表达：口语短句；拒绝首先其次最后、综上所述、研报腔、「规则/成本」空话循环。\n");
        prompt.append("8. 结构：3-6 个人话 ## 小节；正文不要 ## 参考资料 / 资料来源 / 延伸阅读。\n");
        prompt.append("9. 元叙述/写作课/信息差方法论 → 直接重罚到不可发布；像 AI 模板最高 55 分并要求重写。\n");
        if (hasText(planBlock)) {
            prompt.append("\n写作计划（检查成稿是否落实核心判断，而不是复述计划）：\n")
                    .append(truncate(planBlock, 1200)).append("\n");
        }
        if (hasText(evidenceBlock)) {
            prompt.append("\n证据线索（只可核对事实边界，不可要求正文照抄）：\n")
                    .append(truncate(evidenceBlock, 1400)).append("\n");
        }
        prompt.append("\n正文 Markdown：\n").append(markdown).append("\n");
        prompt.append("\n只返回 JSON：\n");
        prompt.append("{\n");
        prompt.append("  \"score\": 0-100,\n");
        prompt.append("  \"issues\": [\"问题1\", \"问题2\"],\n");
        prompt.append("  \"fixActions\": [\"修订动作1：具体改哪里\", \"修订动作2\"],\n");
        prompt.append("  \"comment\": \"一句话总评\"\n");
        prompt.append("}\n");
        prompt.append("issues 写诊断，fixActions 写编辑可执行的改法，都控制在 6 条以内。");
        return prompt.toString();
    }

    private String buildRevisionPrompt(String topic,
                                       String category,
                                       String markdown,
                                       Review review,
                                       String evidenceBlock,
                                       String planBlock) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("按审稿意见定向改写，产出「能涨阅读和关注」的完整微信 Markdown。\n");
        prompt.append("选题：").append(topic).append("\n栏目：").append(category).append("\n");
        prompt.append("目标：关于「").append(topic).append("」——让人想点开、想读完、想留言/转发、愿意点关注。\n");
        prompt.append("硬约束：\n");
        prompt.append("1. 保留一个核心判断，不要换成另一篇文章。\n");
        prompt.append("2. 禁止编造证据里没有的精确数据、政策条文、公司动作。\n");
        prompt.append("3. 重写 # 标题为 14-26 字点击向标题：情绪词（心慌/吃亏/沉默/别急）+ 好奇缺口 + 点到选题；")
                .append("必须带 ：或？或—；禁止一文看懂/和你有什么关系/震惊炸裂/复述新闻原题。\n");
        prompt.append("4. 开头 3 秒场景/数字/对话砸开，120 字内亮判断；中段加小钩子促完读。\n");
        prompt.append("5. 每节至少 1 个具体场景、角色或后果；删掉选题名后不能通用。\n");
        prompt.append("6. 3-6 个人话 ## 小节 + 可执行清单；禁止 ## 参考资料 / 资料来源 / 延伸阅读。\n");
        prompt.append("7. 文末：具体留言问题 + 一句轻关注引导（不硬广、不诱导分享）。\n");
        prompt.append("8. 删除后台/元叙述与参考资料小节；只输出完整 Markdown，从 # 标题开始。\n");
        prompt.append("9. 禁止「规则/成本/选择空间」空话；换成这个选题里真实动作和后果。\n");
        prompt.append("10. 加长到去掉空白后至少 2000 中文字，至少 14 个自然段；缺字就扩写场景与动作，不要注水空话。\n");
        prompt.append("11. 禁止正文出现：提示词、写作提纲、选题方案、运营建议、仿写、洗稿、参考资料列表。\n");
        if (review.getFixActions() != null && !review.getFixActions().isEmpty()) {
            prompt.append("\n优先执行的修订动作：\n");
            for (String action : review.getFixActions()) {
                if (hasText(action)) {
                    prompt.append("- ").append(action.trim()).append("\n");
                }
            }
        }
        if (review.getIssues() != null && !review.getIssues().isEmpty()) {
            prompt.append("\n审稿问题：\n");
            for (String issue : review.getIssues()) {
                if (hasText(issue)) {
                    prompt.append("- ").append(issue.trim()).append("\n");
                }
            }
        }
        if (hasText(review.getComment())) {
            prompt.append("总评：").append(review.getComment().trim()).append("\n");
        }
        if (hasText(planBlock)) {
            prompt.append("\n原计划核心（可改进小节标题，但要保住判断）：\n")
                    .append(truncate(planBlock, 1000)).append("\n");
        }
        if (hasText(evidenceBlock)) {
            prompt.append("\n可用证据线索：\n").append(truncate(evidenceBlock, 1200)).append("\n");
        }
        prompt.append("\n待修订原稿：\n").append(markdown);
        return prompt.toString();
    }

    private Object firstPresent(Map<String, Object> parsed, String... keys) {
        if (parsed == null) {
            return null;
        }
        for (String key : keys) {
            if (parsed.containsKey(key) && parsed.get(key) != null) {
                return parsed.get(key);
            }
        }
        return null;
    }

    private void addIssue(List<String> issues, String issue) {
        if (hasText(issue) && !issues.contains(issue)) {
            issues.add(issue);
        }
    }

    private List<String> trimIssues(List<String> issues, int max) {
        if (issues == null || issues.size() <= max) {
            return issues == null ? new ArrayList<String>() : issues;
        }
        return new ArrayList<>(issues.subList(0, max));
    }

    private String firstTitle(String markdown) {
        for (String line : defaultText(markdown, "").split("\\r?\\n")) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return "";
    }

    private boolean containsBackstage(String value) {
        String text = defaultText(value, "");
        for (String phrase : BACKSTAGE_PHRASES) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String value, int max) {
        String text = defaultText(value, "");
        return text.length() <= max ? text : text.substring(0, max);
    }

    private List<String> mergeIssues(List<String> primary, List<String> fallback) {
        List<String> merged = new ArrayList<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (fallback != null) {
            for (String item : fallback) {
                if (hasText(item) && !merged.contains(item)) {
                    merged.add(item);
                }
                if (merged.size() >= 8) {
                    break;
                }
            }
        }
        return merged;
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
        return parseInt(config(CFG_MIN_SCORE), DEFAULT_MIN_SCORE);
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

    private String safeMessage(String message) {
        if (!hasText(message)) {
            return "unknown";
        }
        String text = message.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+\\-/=]+", "$1***");
        return text.length() <= 160 ? text : text.substring(0, 160) + "...";
    }

    @Data
    @AllArgsConstructor
    public static class Review {
        private int score;
        private List<String> issues;
        private String comment;
        private int minScore;
        private List<String> fixActions;

        public Review(int score, List<String> issues, String comment, int minScore) {
            this(score, issues, comment, minScore, new ArrayList<String>());
        }

        public boolean needsRevision() {
            return score < minScore;
        }
    }
}
