package com.example.website.service.content;

import static com.example.website.service.content.ContentTextUtils.escape;

/**
 * Minimal Markdown-to-HTML converter for content-factory articles.
 *
 * <p>Extracted verbatim from {@code ContentArticleService.markdownToHtml}. Supports the
 * limited subset the article prompt is constrained to emit: {@code # / ## } headings,
 * {@code > } blockquotes, {@code - } list items, and plain paragraphs.
 */
public final class MarkdownHtmlConverter {

    private MarkdownHtmlConverter() {
    }

    public static String markdownToHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        boolean inList = false;
        for (String line : markdown.split("\\r?\\n")) {
            if (line.startsWith("# ")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<h1>").append(escape(line.substring(2))).append("</h1>");
            } else if (line.startsWith("## ")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<h2>").append(escape(line.substring(3))).append("</h2>");
            } else if (line.startsWith("> ")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<blockquote>").append(escape(line.substring(2))).append("</blockquote>");
            } else if (line.startsWith("- ")) {
                if (!inList) {
                    html.append("<ul>");
                    inList = true;
                }
                html.append("<li>").append(escape(line.substring(2))).append("</li>");
            } else if (line.trim().isEmpty()) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("\n");
            } else {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<p>").append(escape(line)).append("</p>");
            }
        }
        if (inList) {
            html.append("</ul>");
        }
        return html.toString();
    }
}
