package com.example.website.service.content;

import com.example.website.entity.ContentArticle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishableArticleContractTests {

    private PublishableArticleContract contract;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        contract = new PublishableArticleContract(objectMapper);
    }

    @Test
    void writerFallbackBlocksPublishEvenWithHighStructuralScore() {
        ContentArticle article = baseArticle();
        article.setRiskTipsJson("[\"AI 正文生成失败，已回退本地模板\"]");
        article.setCoverImageUrl("https://cdn.example/cover.png");
        article.setInlineImagesJson(readyInlineImagesJson());

        PublishableArticleContract.Evaluation evaluation = contract.evaluateArticle(article);
        assertTrue(evaluation.blocksPublish());
        assertEquals("block", evaluation.getDecision());
    }

    @Test
    void missingInlineImagesBlocksPublish() {
        ContentArticle article = baseArticle();
        article.setCoverImageUrl("https://cdn.example/cover.png");
        article.setInlineImagesJson("[]");

        PublishableArticleContract.Evaluation evaluation = contract.evaluateArticle(article);
        assertTrue(evaluation.blocksPublish());
        assertTrue(evaluation.getIssues().stream().anyMatch(issue -> issue.contains("inline images")));
    }

    @Test
    void weakTitlePhraseIsPenalized() {
        PublishableArticleContract.Evaluation evaluation = contract.evaluateDraft(
                "这件事和你有什么关系",
                sampleMarkdown(),
                sampleHtml(),
                "standard",
                true,
                true,
                false
        );
        assertTrue(evaluation.getScore() < 100);
        assertTrue(evaluation.getIssues().stream().anyMatch(issue -> issue.contains("Weak/generic title")));
    }

    @Test
    void readyVisualsAndBodyCanPassContract() throws Exception {
        ContentArticle article = baseArticle();
        article.setTitle("平台规则改了：创作者该先算清哪三笔账");
        article.setCoverImageUrl("https://cdn.example/cover.png");
        article.setInlineImagesJson(readyInlineImagesJson());
        article.setContentHtml(sampleHtmlWithImages());
        article.setContentMarkdown(sampleMarkdown());
        article.setEvidenceJson("[{\"id\":\"e1\",\"title\":\"source\"}]");
        article.setPlanJson("{\"thesis\":\"rules matter\"}");
        article.setRiskTipsJson("[]");

        PublishableArticleContract.Evaluation evaluation = contract.evaluateArticle(article);
        assertFalse(evaluation.blocksPublish(), String.join("; ", evaluation.getIssues()));
        assertEquals("pass", evaluation.getDecision());
        assertTrue(evaluation.getScore() >= PublishableArticleContract.MIN_PUBLISH_SCORE);
    }

    private ContentArticle baseArticle() {
        ContentArticle article = new ContentArticle();
        article.setTitle("平台规则改了：创作者该先算清哪三笔账");
        article.setContentMarkdown(sampleMarkdown());
        article.setContentHtml(sampleHtml());
        article.setEvidenceJson("[{\"id\":\"e1\"}]");
        article.setPlanJson("{\"thesis\":\"x\"}");
        article.setRiskTipsJson("[]");
        return article;
    }

    private String readyInlineImagesJson() {
        try {
            ArticleInlineImage one = ArticleInlineImage.builder()
                    .slotId("inline-1")
                    .sectionKey("s1")
                    .sectionTitle("为什么这次规则改动更伤人")
                    .alt("规则改动")
                    .sourceType("ai")
                    .sourceUrl("https://cdn.example/a.png")
                    .sha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                    .status("READY")
                    .build();
            ArticleInlineImage two = ArticleInlineImage.builder()
                    .slotId("inline-2")
                    .sectionKey("s2")
                    .sectionTitle("三笔账怎么算")
                    .alt("三笔账")
                    .sourceType("ai")
                    .sourceUrl("https://cdn.example/b.png")
                    .sha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                    .status("READY")
                    .build();
            return objectMapper.writeValueAsString(Arrays.asList(one, two));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String sampleMarkdown() {
        StringBuilder md = new StringBuilder();
        md.append("# 标题\n\n");
        md.append("开头先把冲突说清楚：规则变了，创作者如果还按旧习惯发内容，会先亏在分发而不是内容本身。")
                .append("很多团队第一反应是加码投放，但真正先被改写的是推荐权重、结算口径和合规边界。\n\n");
        md.append("## 为什么这次规则改动更伤人\n\n");
        for (int i = 0; i < 12; i++) {
            md.append("这一段解释规则如何改变推荐与结算，并给出可核对的行业事实与后果分析。")
                    .append("创作者看到的不是抽象算法，而是完播、互动和账号标签一起被重新定价。")
                    .append("如果只盯着单日播放，很容易误判为内容质量波动。段落")
                    .append(i).append("。\n\n");
        }
        md.append("## 三笔账怎么算\n\n");
        for (int i = 0; i < 12; i++) {
            md.append("从流量、转化和合规成本三边把账算清楚，避免只看表面曝光。")
                    .append("同一条内容在不同分发位的单位成本会差出几倍，必须拆开算。")
                    .append("真正危险的是把短期投放效果当成长期内容资产。段落")
                    .append(i).append("。\n\n");
        }
        md.append("## 普通人可以怎么做\n\n");
        for (int i = 0; i < 12; i++) {
            md.append("给出可执行动作，而不是空泛建议。先固定选题标准，再压缩试错周期，最后补齐证据链。")
                    .append("每周复盘时，把被限流、被降权、被投诉的样本单独归档。段落")
                    .append(i).append("。\n\n");
        }
        md.append("你怎么看？欢迎在评论区聊聊你的判断。\n");
        md.append("觉得有用就点个关注，下次热点我继续第一时间拆清楚。\n");
        return md.toString();
    }

    private String sampleHtml() {
        return MarkdownHtmlConverter.markdownToHtml(sampleMarkdown(), Collections.emptyList());
    }

    private String sampleHtmlWithImages() throws Exception {
        ArticleInlineImage one = ArticleInlineImage.builder()
                .slotId("inline-1")
                .sectionKey("s1")
                .sectionTitle("为什么这次规则改动更伤人")
                .alt("规则改动")
                .sourceType("ai")
                .sourceUrl("https://cdn.example/a.png")
                .sha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .status("READY")
                .build();
        ArticleInlineImage two = ArticleInlineImage.builder()
                .slotId("inline-2")
                .sectionKey("s2")
                .sectionTitle("三笔账怎么算")
                .alt("三笔账")
                .sourceType("ai")
                .sourceUrl("https://cdn.example/b.png")
                .sha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                .status("READY")
                .build();
        return MarkdownHtmlConverter.markdownToHtml(sampleMarkdown(), Arrays.asList(one, two));
    }
}
