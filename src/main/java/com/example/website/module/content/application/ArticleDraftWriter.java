package com.example.website.module.content.application;

import com.example.website.module.content.api.dto.ContentArticleGenerateRequest;
import com.example.website.module.ai.application.AiChatUpstreamClient;
import com.example.website.module.settings.application.SysConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.example.website.module.content.application.ContentTextUtils.asList;
import static com.example.website.module.content.application.ContentTextUtils.asString;
import static com.example.website.module.content.application.ContentTextUtils.defaultText;
import static com.example.website.module.content.application.ContentTextUtils.extractJsonObject;
import static com.example.website.module.content.application.ContentTextUtils.firstText;
import static com.example.website.module.content.application.ContentTextUtils.hasText;
import static com.example.website.module.content.application.ContentTextUtils.readStringList;
import static com.example.website.module.content.application.ContentTextUtils.trimToNull;
import static com.example.website.module.content.application.MarkdownHtmlConverter.markdownToHtml;

import static com.example.website.module.content.application.ArticleDraftText.*;

/** Writer endpoint selection, AI generation, parsing and fallback; no article persistence. */
@Service
@RequiredArgsConstructor
public class ArticleDraftWriter {
    private final SysConfigService configService;
    private final ObjectMapper objectMapper;
    private final AiChatUpstreamClient aiChatUpstreamClient;

    ContentGenerationResult generateArticleContent(String topic,
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

    private String config(String key) {
        return configService.getValue(key).orElse("");
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
}
