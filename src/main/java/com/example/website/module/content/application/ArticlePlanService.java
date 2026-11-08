package com.example.website.module.content.application;

import com.example.website.module.ai.application.AiChatUpstreamClient;
import com.example.website.module.settings.application.SysConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.example.website.module.content.application.ContentTextUtils.asString;
import static com.example.website.module.content.application.ContentTextUtils.defaultText;
import static com.example.website.module.content.application.ContentTextUtils.extractJsonObject;
import static com.example.website.module.content.application.ContentTextUtils.hasText;
import static com.example.website.module.content.application.ContentTextUtils.readStringList;
import static com.example.website.module.content.application.ContentTextUtils.trimToNull;

/** TrendPublish-style pre-writing outline. */
@Service
@RequiredArgsConstructor
public class ArticlePlanService {

    public static final String CFG_ENABLED = "content.plan.enabled";

    private final SysConfigService configService;
    private final AiChatUpstreamClient aiChatUpstreamClient;
    private final ObjectMapper objectMapper;

    public ArticlePlan plan(String topic, String category, String angle, String audience, String evidenceBlock) {
        String subject = trimToNull(topic);
        if (subject == null || !enabled()) {
            return ArticlePlan.empty();
        }
        if (!hasText(config("ai.chat.baseUrl"))) {
            return localPlan(subject, angle, evidenceBlock);
        }
        try {
            List<AiChatUpstreamClient.ChatMessage> messages = new ArrayList<>();
            messages.add(new AiChatUpstreamClient.ChatMessage("system",
                    "你是微信公众号增长策划，目标是打开率、完读、留言和关注。只返回 JSON，不要 Markdown 围栏，不要写正文。"));
            messages.add(new AiChatUpstreamClient.ChatMessage("user",
                    buildPrompt(subject, category, angle, audience, evidenceBlock)));
            AiChatUpstreamClient.ChatCompletionResult result = aiChatUpstreamClient.completeQuick(
                    config("ai.chat.baseUrl"),
                    config("ai.chat.apiKey"),
                    defaultText(config("ai.chat.defaultModel"), "mimo-v2.5-pro"),
                    messages
            );
            Map<String, Object> parsed = tryReadMap(extractJsonObject(result.getContent()));
            if (parsed == null) {
                return localPlan(subject, angle, evidenceBlock);
            }
            ArticlePlan plan = toPlan(parsed);
            return plan.isEmpty() ? localPlan(subject, angle, evidenceBlock) : plan;
        } catch (Exception e) {
            return localPlan(subject, angle, evidenceBlock);
        }
    }

    private String buildPrompt(String topic, String category, String angle, String audience, String evidenceBlock) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("为「").append(topic).append("」做一篇「能涨阅读和关注」的微信推文内部故事线，不要写正文，不要写写作课。\n");
        prompt.append("栏目：").append(category).append("\n");
        prompt.append("增长目标：打开率（标题勾情绪+好奇）→ 完读（场景+节奏）→ 留言/转发 → 自然关注。\n");
        prompt.append("不是研报结构，不是信息差方法论。\n");
        if (hasText(evidenceBlock)) {
            prompt.append("\n证据线索（只用于 grounding，禁止照抄，证据不足时主张要保守）：\n")
                    .append(evidenceBlock).append("\n");
        }
        prompt.append("\n策划合同：\n");
        prompt.append("1. 只立一个核心判断（谁会疼/谁会赚/旧办法哪里失效），要让读者觉得「说的就是我」。\n");
        prompt.append("2. titleHook：对外标题草案 14-26 字，必须勾情绪+好奇（别急/吃亏/沉默/谁在买单），带 ：？—，")
                .append("点到选题具体点；禁止复述新闻原题、一文看懂、震惊体。\n");
        prompt.append("3. hook：具体场景、对话、数字或反常识事实，120 字内砸到痛点；禁止「近年来」「随着」。\n");
        prompt.append("4. 4-6 个小节，节奏：扎心场景 → 拆穿 → 谁赚钱谁买单 → 坑 → 现在就能做的 2-3 步。\n");
        prompt.append("5. sections 每项：读者可见小标题 | 意图 | 关键主张 | 证据/例子提示 | 配图提示。\n");
        prompt.append("   小标题必须是人话、像会点开的目录；禁止「概述」「背景」「总结」「方法论」「栏目定位」。\n");
        prompt.append("6. 禁弱标题思路：和你有什么关系、一文看懂、深度解析、背后的信息差。\n");
        prompt.append("7. callToAction：一条能让人留言站队/吐槽的具体问题 + 一句轻关注引导（不硬广）。\n");
        prompt.append("返回严格 JSON：titleHook, hook, coreJudgment, sections, callToAction。sections 必须是字符串数组。");
        return prompt.toString();
    }

    private ArticlePlan localPlan(String topic, String angle, String evidenceBlock) {
        List<String> sections = new ArrayList<>();
        sections.add("先从最扎心的那一幕说起 | 打开完读 | 120 字内亮判断 | 用「" + topic + "」具体场景 | 场景配图");
        sections.add("热闹背后，真正贵的是什么 | 拆定价 | 谁的时间/钱/机会变贵 | 点名具体角色 | 对比图");
        sections.add("谁在赚钱，谁在买单 | 利益链 | 平台/从业者/普通人 | 可观察后果 | 关系图");
        sections.add("最容易踩的那个坑 | 纠常见误读 | 给 2 个核对动作 | 场景化 | 对比图");
        sections.add("现在就能做的几步 | 可执行 | 2-3 个下一步 | 对应公开信息 | 清单图");
        if (hasText(evidenceBlock)) {
            sections.add("哪些能写实、哪些只能分析 | 守事实边界 | 不编造精确数据 | 引用公开线索 | 来源示意");
        }
        String shortTopic = topic.length() > 12 ? topic.substring(0, 12) : topic;
        return new ArticlePlan(
                "别急着转发：" + shortTopic + "真正改的是什么",
                "刷到「" + topic + "」时先别站队：用一个具体场景开场，120 字内讲清谁会疼、谁会赚。",
                "关于「" + topic + "」：热闹会退，真正要盯的是你自己哪一步会被重新定价，以及怎么少踩坑。",
                sections,
                "围绕「" + topic + "」，你踩过最冤的坑是什么？留言聊聊。觉得有用就点个关注，下次热点继续第一时间拆。"
        );
    }

    private ArticlePlan toPlan(Map<String, Object> parsed) {
        List<String> sections = readStringList(parsed.get("sections"));
        return new ArticlePlan(
                trimToNull(asString(parsed.get("titleHook"))),
                trimToNull(asString(parsed.get("hook"))),
                trimToNull(asString(parsed.get("coreJudgment"))),
                sections,
                trimToNull(asString(parsed.get("callToAction")))
        );
    }

    private boolean enabled() {
        return !"false".equalsIgnoreCase(config(CFG_ENABLED));
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

    @Data
    @AllArgsConstructor
    public static class ArticlePlan {
        /** Outward click title draft — emotion + curiosity for open-rate. */
        private String titleHook;
        private String hook;
        private String coreJudgment;
        private List<String> sections;
        private String callToAction;

        public static ArticlePlan empty() {
            return new ArticlePlan(null, null, null, new ArrayList<>(), null);
        }

        public boolean isEmpty() {
            return !hasText(titleHook) && !hasText(hook) && !hasText(coreJudgment)
                    && (sections == null || sections.isEmpty()) && !hasText(callToAction);
        }

        public String toPromptBlock() {
            if (isEmpty()) {
                return "";
            }
            StringBuilder block = new StringBuilder();
            if (hasText(titleHook)) {
                block.append("对外标题草案（优先采用，可微调）：").append(titleHook.trim()).append("\n");
            }
            if (hasText(hook)) {
                block.append("开头：").append(hook.trim()).append("\n");
            }
            if (hasText(coreJudgment)) {
                block.append("核心判断：").append(coreJudgment.trim()).append("\n");
            }
            if (sections != null && !sections.isEmpty()) {
                block.append("分节：\n");
                for (String section : sections) {
                    if (hasText(section)) {
                        block.append("- ").append(section.trim()).append("\n");
                    }
                }
            }
            if (hasText(callToAction)) {
                block.append("结尾互动：").append(callToAction.trim()).append("\n");
            }
            return block.toString();
        }
    }
}
