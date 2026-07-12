package com.example.website.service.content;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dependency-free tests for the TrendPublish-style quality pipeline's pure logic and DTO
 * behaviour: evidence query derivation, prompt-block rendering, and the review/revision
 * thresholds. Anything that needs the model or a live search source is left to integration.
 */
class ContentPipelineLogicTests {

    // ---- EvidenceService.buildQueries -----------------------------------------------------

    @Test
    void buildQueriesAlwaysLeadsWithTheTopicAndCapsAtMax() {
        List<String> one = EvidenceService.buildQueries("小米汽车降价", "对普通人的影响", "科技 / 互联网", 1);
        assertEquals(1, one.size());
        assertEquals("小米汽车降价", one.get(0));

        List<String> two = EvidenceService.buildQueries("小米汽车降价", "对普通人的影响", "科技 / 互联网", 2);
        assertEquals(2, two.size());
        assertEquals("小米汽车降价", two.get(0));
        assertEquals("小米汽车降价 对普通人的影响", two.get(1));
    }

    @Test
    void buildQueriesFallsBackToCategoryWhenAngleMissing() {
        List<String> queries = EvidenceService.buildQueries("考研人数下降", null, "教育 / 职场", 3);
        assertEquals(Arrays.asList("考研人数下降", "考研人数下降 教育 / 职场"), queries);
    }

    @Test
    void buildQueriesWithNoAngleOrCategoryReturnsOnlyTopic() {
        List<String> queries = EvidenceService.buildQueries("某热点", "  ", "  ", 3);
        assertEquals(1, queries.size());
        assertEquals("某热点", queries.get(0));
    }

    // ---- EvidenceService.EvidenceResult ---------------------------------------------------

    @Test
    void evidenceResultEmptyRendersBlankBlock() {
        EvidenceService.EvidenceResult empty = EvidenceService.EvidenceResult.empty();
        assertTrue(empty.isEmpty());
        assertEquals("", empty.toPromptBlock());
    }

    @Test
    void evidenceResultRendersNumberedLines() {
        List<WebSearchService.SearchResult> hits = new ArrayList<>();
        hits.add(new WebSearchService.SearchResult("标题A", "摘要A", "https://a.example"));
        hits.add(new WebSearchService.SearchResult("标题B", "摘要B", null));
        EvidenceService.EvidenceResult result = new EvidenceService.EvidenceResult(hits, new ArrayList<>());

        assertFalse(result.isEmpty());
        String block = result.toPromptBlock();
        assertTrue(block.contains("1. 标题A：摘要A（来源：https://a.example）"));
        assertTrue(block.contains("2. 标题B：摘要B"));
    }

    // ---- ArticlePlanService.ArticlePlan ---------------------------------------------------

    @Test
    void articlePlanEmptyIsEmptyAndBlank() {
        ArticlePlanService.ArticlePlan plan = ArticlePlanService.ArticlePlan.empty();
        assertTrue(plan.isEmpty());
        assertEquals("", plan.toPromptBlock());
    }

    @Test
    void articlePlanRendersAllPresentSections() {
        ArticlePlanService.ArticlePlan plan = new ArticlePlanService.ArticlePlan(
                "用一个反常识判断开头",
                "热点只是入口，规则才是关键",
                Arrays.asList("一、为什么会火", "二、真正的看点"),
                "你怎么看？留言聊聊");
        assertFalse(plan.isEmpty());

        String block = plan.toPromptBlock();
        assertTrue(block.contains("开头：用一个反常识判断开头"));
        assertTrue(block.contains("核心判断：热点只是入口，规则才是关键"));
        assertTrue(block.contains("- 一、为什么会火"));
        assertTrue(block.contains("- 二、真正的看点"));
        assertTrue(block.contains("结尾互动：你怎么看？留言聊聊"));
    }

    // ---- ArticleReviewService.Review ------------------------------------------------------

    @Test
    void reviewNeedsRevisionOnlyBelowMinScore() {
        assertTrue(new ArticleReviewService.Review(60, new ArrayList<>(), "偏弱", 75).needsRevision());
        assertFalse(new ArticleReviewService.Review(75, new ArrayList<>(), "达标", 75).needsRevision());
        assertFalse(new ArticleReviewService.Review(90, new ArrayList<>(), "很好", 75).needsRevision());
    }

    // ---- EditorialDecisionService.Decision ------------------------------------------------

    @Test
    void editorialDecisionSkipAndPickCarryReason() {
        EditorialDecisionService.Decision skip = EditorialDecisionService.Decision.skip("全部太弱");
        assertTrue(skip.isSkip());
        assertEquals("全部太弱", skip.getReason());

        java.util.Map<String, Object> topic = new java.util.LinkedHashMap<>();
        topic.put("title", "选中的标题");
        EditorialDecisionService.Decision pick = EditorialDecisionService.Decision.pick(topic, "信息差最强");
        assertFalse(pick.isSkip());
        assertEquals("选中的标题", pick.getTopic().get("title"));
        assertEquals("信息差最强", pick.getReason());
    }

    // ---- FallbackCoverImageService.extractImageUrl ----------------------------------------

    @Test
    void fallbackCoverParsesImageUrlFromGeneratingEvent() {
        FallbackCoverImageService service = new FallbackCoverImageService(null, new com.fasterxml.jackson.databind.ObjectMapper(), null);
        String sse = String.join("\n",
                "data: {\"event\":\"start\",\"time\":1,\"logId\":\"x\"}",
                "",
                "data: {\"event\":\"ping\",\"time\":2,\"logId\":\"x\"}",
                "",
                "data: {\"event\":\"generating\",\"data\":{\"result\":\"![](https://cdn.oreateai.com/gpt-image/abc_0.png)\",\"is_end\":false}}",
                "",
                "data: {\"event\":\"end\",\"time\":3,\"logId\":\"x\"}");
        assertEquals("https://cdn.oreateai.com/gpt-image/abc_0.png", service.extractImageUrl(sse));
    }

    @Test
    void fallbackCoverReturnsNullWhenNoImageInStream() {
        FallbackCoverImageService service = new FallbackCoverImageService(null, new com.fasterxml.jackson.databind.ObjectMapper(), null);
        String sse = String.join("\n",
                "data: {\"event\":\"start\",\"time\":1}",
                "data: {\"event\":\"end\",\"time\":2}");
        org.junit.jupiter.api.Assertions.assertNull(service.extractImageUrl(sse));
    }
}
