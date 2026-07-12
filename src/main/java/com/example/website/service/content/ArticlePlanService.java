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
 * Article plan (文章计划), the TrendPublish step that drafts a structured outline before any
 * prose is written. A plan — hook, section beats, key judgment, call to action — keeps the
 * writer from rambling and gives the writer prompt a spine to fill in, which is where most of
 * the "reads like a template" problem comes from.
 *
 * <p>Optional and best-effort: controlled by {@code content.plan.enabled}; any failure returns
 * an empty plan and the writer proceeds with its own structure, exactly as before.
 */
@Service
@RequiredArgsConstructor
public class ArticlePlanService {

    public static final String CFG_ENABLED = "content.plan.enabled";

    private final SysConfigService configService;
    private final AiChatUpstreamClient aiChatUpstreamClient;
    private final ObjectMapper objectMapper;

    /**
     * Build a plan for {@code topic}. Evidence, when present, is offered so the outline can point
     * sections at concrete material. Returns {@link ArticlePlan#empty()} when disabled or on error.
     */
    public ArticlePlan plan(String topic,
                            String category,
                            String angle,
                            String audience,
                            String evidenceBlock) {
        String subject = trimToNull(topic);
        if (subject == null || !enabled() || !hasText(config("ai.chat.baseUrl"))) {
            return ArticlePlan.empty();
        }
        try {
            List<AiChatUpstreamClient.ChatMessage> messages = new ArrayList<>();
            messages.add(new AiChatUpstreamClient.ChatMessage("system",
                    "你是公众号「早一步信息差」的资深策划，负责在动笔前先规划文章结构。只输出 JSON，不要 Markdown 代码块。"));
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
                return ArticlePlan.empty();
            }
            return toPlan(parsed);
        } catch (Exception e) {
            return ArticlePlan.empty();
        }
    }

    private String buildPrompt(String topic,
                               String category,
                               String angle,
                               String audience,
                               String evidenceBlock) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请为下面的公众号选题先规划一份写作大纲，不要写正文。\n");
        prompt.append("选题：").append(topic).append("\n");
        prompt.append("栏目：").append(category).append("\n");
        if (hasText(angle)) {
            prompt.append("切入角度：").append(angle.trim()).append("\n");
        }
        if (hasText(audience)) {
            prompt.append("目标读者：").append(audience.trim()).append("\n");
        }
        if (hasText(evidenceBlock)) {
            prompt.append("\n可参考的事实线索（可用于支撑分节，不要照抄）：\n").append(evidenceBlock).append("\n");
        }
        prompt.append("\n规划要求：开头要有强场景或反常识判断；中段 4-6 个小节，每节都回答“这和读者有什么关系”；");
        prompt.append("要有一个有记忆点的核心判断；结尾收束到读者处境并引导互动。\n");
        prompt.append("请严格输出 JSON，字段：\n");
        prompt.append("  hook（开头切入方式，一句话）,\n");
        prompt.append("  coreJudgment（贯穿全文的核心判断，一句话）,\n");
        prompt.append("  sections（字符串数组，每个元素是一个小节的标题+要点）,\n");
        prompt.append("  callToAction（结尾互动引导，一句话）。");
        return prompt.toString();
    }

    private ArticlePlan toPlan(Map<String, Object> parsed) {
        List<String> sections = readStringList(parsed.get("sections"));
        return new ArticlePlan(
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

    /**
     * A structured outline. {@link #toPromptBlock()} renders it for injection into the writer
     * prompt; the raw fields are persisted so the plan is visible in the run for review.
     */
    @Data
    @AllArgsConstructor
    public static class ArticlePlan {
        private String hook;
        private String coreJudgment;
        private List<String> sections;
        private String callToAction;

        public static ArticlePlan empty() {
            return new ArticlePlan(null, null, new ArrayList<>(), null);
        }

        public boolean isEmpty() {
            return !hasText(hook) && !hasText(coreJudgment)
                    && (sections == null || sections.isEmpty()) && !hasText(callToAction);
        }

        /** Render the plan as a writer-prompt block; empty string when there is nothing to say. */
        public String toPromptBlock() {
            if (isEmpty()) {
                return "";
            }
            StringBuilder block = new StringBuilder();
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
