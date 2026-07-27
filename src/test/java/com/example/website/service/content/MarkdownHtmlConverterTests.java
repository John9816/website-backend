package com.example.website.service.content;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownHtmlConverterTests {

    @Test
    void placesInlineImageAfterMatchingH2() {
        ArticleInlineImage image = ArticleInlineImage.builder()
                .slotId("inline-1")
                .sectionKey("s1")
                .sectionTitle("核心判断")
                .alt("核心判断配图")
                .sourceType("ai")
                .sourceUrl("https://cdn.example/x.png")
                .sha256("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
                .status("READY")
                .build();

        String html = MarkdownHtmlConverter.markdownToHtml(
                "# 标题\n\n导语\n\n## 核心判断\n\n正文\n",
                Collections.singletonList(image)
        );

        assertTrue(html.contains("核心判断"));
        assertTrue(html.contains("<h2"));
        assertTrue(html.contains("https://cdn.example/x.png"));
        assertTrue(html.indexOf("核心判断") < html.indexOf("https://cdn.example/x.png"));
        assertTrue(html.contains("data-content-factory=\"wechat\""));
    }

    @Test
    void matchesSectionTitleDespitePunctuationDrift() {
        ArticleInlineImage image = ArticleInlineImage.builder()
                .slotId("inline-1")
                .sectionKey("s1")
                .sectionTitle("核心判断：平台在改规则")
                .alt("配图")
                .sourceType("ai")
                .sourceUrl("https://cdn.example/y.png")
                .sha256("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
                .status("READY")
                .build();

        String html = MarkdownHtmlConverter.markdownToHtml(
                "## 核心判断 平台在改规则\n\n段落\n",
                Collections.singletonList(image)
        );

        assertTrue(html.contains("https://cdn.example/y.png"));
    }

    @Test
    void doesNotEmitScriptTagsFromMarkdown() {
        String html = MarkdownHtmlConverter.markdownToHtml(
                "## 安全\n\n<script>alert(1)</script>\n",
                Collections.emptyList()
        );
        assertFalse(html.toLowerCase().contains("<script"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void appliesThemeInlineStylesAndBold() {
        String html = MarkdownHtmlConverter.markdownToHtml(
                "# 标题\n\n这是**重点**内容\n\n## 小节\n\n> 金句\n\n- 列表项\n\n---\n\n收尾\n",
                Collections.emptyList(),
                "magazine",
                "科技 / 互联网"
        );
        assertTrue(html.contains("data-layout=\"magazine\""));
        assertTrue(html.contains("<strong"));
        assertTrue(html.contains("重点"));
        assertTrue(html.contains("border-left:5px solid"));
        assertTrue(html.contains("<blockquote"));
        assertTrue(html.contains("<hr"));
        assertTrue(html.contains("style="));
    }

    @Test
    void warmThemeUsesSoftBackground() {
        String html = MarkdownHtmlConverter.markdownToHtml(
                "## 标题\n\n段落\n",
                Collections.emptyList(),
                "warm",
                "emotion_psychology"
        );
        assertTrue(html.contains("data-layout=\"warm\""));
        assertTrue(html.contains("background:#fff4f8") || html.contains("background: #fff4f8")
                || html.contains("#fff4f8"));
    }
}
