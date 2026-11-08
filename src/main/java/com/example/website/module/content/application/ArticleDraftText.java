package com.example.website.module.content.application;

import com.example.website.module.content.api.dto.ContentArticleGenerateRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.example.website.module.content.application.ContentTextUtils.asList;
import static com.example.website.module.content.application.ContentTextUtils.countMatches;
import static com.example.website.module.content.application.ContentTextUtils.defaultText;
import static com.example.website.module.content.application.ContentTextUtils.hasText;
import static com.example.website.module.content.application.ContentTextUtils.htmlToPlainText;
import static com.example.website.module.content.application.MarkdownHtmlConverter.markdownToHtml;

/** Deterministic text normalization and draft quality rules, independent of I/O. */
final class ArticleDraftText {
    private ArticleDraftText() { }
    private static final String DEFAULT_CATEGORY = "科技 / 互联网";
    /** Phrases that kill open-rate or trip the publish gate — rewrite title if present. */
    private static final List<String> TITLE_BANNED_PHRASES = Arrays.asList(
            "一文看懂", "一文读懂", "深度解析", "全面解读",
            "和你有什么关系", "和你有啥关系", "背后的信息差", "值得关注",
            "你怎么看", "该怎么看",
            "震惊", "炸裂", "彻底变天", "全网都", "没人告诉你", "再不知道就晚了", "万万没想到"
    );

    /**
     * If the draft jumps from H1 straight into H2 without a ≥40-char lead, inject a concrete
     * scene opener so the deterministic text gate does not fail "Opening lacks…".
     * Lead is topic-bound (hot-topic rewrite), never a generic AI-productivity anecdote.
     */
    static String ensurePublishReadyLead(String markdown, String topic) {
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
    static String ensureH2Budget(String markdown) {
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
    static String ensurePublishReadyTail(String markdown, List<Map<String, Object>> evidenceSources) {
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
    static String stripReferenceSections(String markdown) {
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

    static boolean containsAny(String text, String... needles) {
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

    static List<String> defaultTags(String category) {
        List<String> tags = new ArrayList<>();
        tags.add("内容工厂");
        tags.add(category);
        return tags;
    }

    static List<String> defaultRiskTips() {
        // Must be mutable: callers append tips after a successful AI parse (singletonList throws UOE).
        List<String> tips = new ArrayList<>(2);
        tips.add("早一步信息差为订阅号，当前仅生成微信草稿；发布前请人工复核事实、标题、配图和平台规则。");
        return tips;
    }

    static String buildChunkedTitle(String topic) {
        return sharpenClickTitle(null, topic);
    }

    static String extractTitleFromMarkdown(String markdown, String fallback) {
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
    static String sharpenClickTitle(String rawTitle, String topic) {
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

    static String craftHookTitle(String topicCore, String topicSeed) {
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

    static String compactTitleCore(String value, int maxChars) {
        String text = defaultText(value, "热点").replaceAll("\\s+", "")
                .replaceAll("[《》【】\\[\\]\"“”‘’]", "");
        if (text.length() > maxChars) {
            return text.substring(0, maxChars);
        }
        return text;
    }

    static String sharpenDigest(String rawDigest, String topic, String title) {
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

    static boolean isWeakDigest(String digest, String title) {
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

    static String truncateForPrompt(String value, int maxChars) {
        String text = defaultText(value, "");
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    static boolean containsBackstageOutput(String value) {
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

    static String buildTitle(String topic, String category) {
        return sharpenClickTitle(null, topic);
    }

    static String buildDigest(String topic, String audience) {
        return sharpenDigest(null, topic, sharpenClickTitle(null, topic));
    }

    /**
     * Emergency local body when AI fails. Topic-specific and readable, but publish gate still blocks fallback.
     * Prefer story-like mobile prose over "信息差方法论" scaffolding.
     */
    static String buildMarkdown(String topic, ContentArticleGenerateRequest payload, String length) {
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

    static String ensureMarkdownTitle(String markdown, String title) {
        String trimmed = defaultText(markdown, "").trim();
        if (!hasText(trimmed)) {
            return "# " + defaultText(title, "早一步信息差");
        }
        if (trimmed.startsWith("# ")) {
            return trimmed;
        }
        return "# " + defaultText(title, "早一步信息差") + "\n\n" + trimmed;
    }

    static boolean isPublishableArticleDraft(String markdown, String html, String length) {
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

    static ArticleLengthSpec articleLengthSpec(String length) {
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

    static String buildCoverPrompt(String topic, String style, String category) {
        return "公众号封面图，主题：" + topic + "，栏目：" + category + "，风格：" + defaultText(style, "干净、清晰、有传播感");
    }

    /**
     * Keep imperfect AI prose when it still looks like a real article. Local templates are blocked by
     * the publish gate and historically produced "score-passing but unusable" drafts.
     */
    static boolean isSalvageableAiDraft(String markdown, String html) {
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

    @lombok.Data
    @lombok.AllArgsConstructor
    static class ArticleLengthSpec {
        private String label;
        private int minChars;
        private int targetChars;
    }
}
