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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.website.service.content.ContentTextUtils.asString;
import static com.example.website.service.content.ContentTextUtils.defaultText;
import static com.example.website.service.content.ContentTextUtils.extractJsonObject;
import static com.example.website.service.content.ContentTextUtils.hasText;
import static com.example.website.service.content.ContentTextUtils.trimToNull;

/**
 * Editorial decision (选题聚类 + 编辑决策), the TrendPublish step that turns a raw list of hot
 * topics into one deliberate choice. Instead of blindly picking the top-ranked candidate, it
 * asks the model to act as an editor: cluster near-duplicate stories, weigh each against the
 * account's positioning and the recent-title history, and pick the single most worthwhile topic
 * with an explicit rationale — or decline the whole batch when nothing clears the bar.
 *
 * <p>Best-effort: when the model is unconfigured, times out, or returns unparseable output the
 * caller falls back to its existing single-pick logic. Controlled by {@code content.decision.enabled}.
 */
@Service
@RequiredArgsConstructor
public class EditorialDecisionService {

    public static final String CFG_ENABLED = "content.decision.enabled";

    private final SysConfigService configService;
    private final AiChatUpstreamClient aiChatUpstreamClient;
    private final ObjectMapper objectMapper;

    /**
     * Choose one topic from {@code candidates}. Returns {@code null} (caller falls back) when the
     * decision layer is disabled/unconfigured, there is nothing to choose from, or the model
     * output cannot be understood. A deliberate "skip" is expressed via {@link Decision#isSkip()}.
     */
    public Decision decide(List<Map<String, Object>> candidates,
                           String category,
                           String instruction,
                           List<String> recentTitles) {
        if (!enabled() || candidates == null || candidates.isEmpty() || !hasText(config("ai.chat.baseUrl"))) {
            return null;
        }
        try {
            List<AiChatUpstreamClient.ChatMessage> messages = new ArrayList<>();
            messages.add(new AiChatUpstreamClient.ChatMessage("system",
                    "你是公众号「早一步信息差」的主编，负责从今天的热点候选里做选题决策。只输出 JSON，不要 Markdown 代码块。"));
            messages.add(new AiChatUpstreamClient.ChatMessage("user",
                    buildPrompt(candidates, category, instruction, recentTitles)));
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
            return toDecision(parsed, candidates);
        } catch (Exception e) {
            // Fall back to the caller's heuristic pick if editorial decisioning is unavailable.
            return null;
        }
    }

    private String buildPrompt(List<Map<String, Object>> candidates,
                               String category,
                               String instruction,
                               List<String> recentTitles) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请从下面的热点候选里，为订阅号「早一步信息差」挑出今天最值得写的 1 个选题。\n");
        prompt.append("账号定位：信息差型热点解读，口语化、有判断、拒绝正式和研报腔。\n");
        prompt.append("当前栏目：").append(category).append("\n");
        if (hasText(instruction)) {
            prompt.append("额外要求：").append(instruction.trim()).append("\n");
        }
        prompt.append("\n候选列表（index 从 0 开始）：\n");
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> topic = candidates.get(i);
            prompt.append(i).append(". ").append(defaultText(asString(topic.get("title")), "(无标题)"));
            String source = asString(topic.get("sourceName"));
            if (hasText(source)) {
                prompt.append("  [来源：").append(source.trim()).append("]");
            }
            String summary = asString(topic.get("summary"));
            if (hasText(summary)) {
                prompt.append("  摘要：").append(summary.trim());
            }
            prompt.append("\n");
        }
        if (recentTitles != null && !recentTitles.isEmpty()) {
            prompt.append("\n最近已经写过的标题（避免重复选题，也不要换个说法写同一件事）：\n");
            for (String title : recentTitles) {
                prompt.append("- ").append(title).append("\n");
            }
        }
        prompt.append("\n决策标准：优先能讲出信息差、和普通人有关、可延展成完整文章的选题；");
        prompt.append("把讲同一件事的候选视为一类，只选其中最佳一个；如果全部候选都太弱或与近期重复，就跳过。\n");
        prompt.append("请严格输出 JSON，字段：\n");
        prompt.append("  selectedIndex（选中候选的 index，跳过时填 -1）,\n");
        prompt.append("  skip（布尔，是否放弃本批全部候选）,\n");
        prompt.append("  title（最终选题标题，可在候选基础上改写得更好）,\n");
        prompt.append("  angle（切入角度）, audience（目标读者）, reason（选它/跳过的理由）,\n");
        prompt.append("  tags（字符串数组）。");
        return prompt.toString();
    }

    private Decision toDecision(Map<String, Object> parsed, List<Map<String, Object>> candidates) {
        boolean skip = Boolean.TRUE.equals(parsed.get("skip"));
        int index = parseIndex(parsed.get("selectedIndex"));
        if (skip && !hasText(asString(parsed.get("title")))) {
            return Decision.skip(trimToNull(asString(parsed.get("reason"))));
        }
        Map<String, Object> base = index >= 0 && index < candidates.size()
                ? new LinkedHashMap<>(candidates.get(index))
                : new LinkedHashMap<>();
        // Let the editor refine the headline while keeping the source candidate's provenance.
        String title = defaultText(asString(parsed.get("title")), asString(base.get("title")));
        if (!hasText(title)) {
            return Decision.skip("模型未给出有效标题");
        }
        base.put("title", title);
        putIfPresent(base, "angle", parsed.get("angle"));
        putIfPresent(base, "audience", parsed.get("audience"));
        putIfPresent(base, "reason", parsed.get("reason"));
        Object tags = parsed.get("tags");
        if (tags instanceof List && !((List<?>) tags).isEmpty()) {
            base.put("tags", tags);
        }
        if (!hasText(asString(base.get("source")))) {
            base.put("source", "editor");
            base.put("sourceName", "主编决策");
        }
        return Decision.pick(base, trimToNull(asString(parsed.get("reason"))));
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        String text = trimToNull(asString(value));
        if (text != null) {
            target.put(key, text);
        }
    }

    private int parseIndex(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        String text = trimToNull(asString(value));
        if (text == null) {
            return -1;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
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
     * The editor's verdict: either a chosen topic (possibly with a refined title) or a deliberate
     * decision to skip the whole batch. {@code reason} is surfaced in the run timeline.
     */
    @Data
    @AllArgsConstructor
    public static class Decision {
        private boolean skip;
        private Map<String, Object> topic;
        private String reason;

        static Decision pick(Map<String, Object> topic, String reason) {
            return new Decision(false, topic, reason);
        }

        static Decision skip(String reason) {
            return new Decision(true, null, reason);
        }
    }
}
