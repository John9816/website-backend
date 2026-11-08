package com.example.website.module.content.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.website.module.content.application.ContentTextUtils.escape;

/**
 * Markdown → WeChat-friendly HTML for content-factory articles.
 *
 * <p>Supports the subset the writer is constrained to emit: headings, blockquotes,
 * unordered/ordered lists, horizontal rules, paragraphs, and basic inline
 * {@code **bold**}/{@code *em*}. Output is wrapped in a themed {@code <section>}
 * with inline styles (WeChat strips external CSS).
 */
public final class MarkdownHtmlConverter {

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern EMPHASIS = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)");
    private static final Pattern ORDERED_ITEM = Pattern.compile("^\\d+\\.\\s+(.*)$");

    private MarkdownHtmlConverter() {
    }

    public static String markdownToHtml(String markdown) {
        return markdownToHtml(markdown, Collections.emptyList(), "clean", null);
    }

    public static String markdownToHtml(String markdown, List<ArticleInlineImage> images) {
        return markdownToHtml(markdown, images, "clean", null);
    }

    public static String markdownToHtml(String markdown,
                                        List<ArticleInlineImage> images,
                                        String layoutTheme) {
        return markdownToHtml(markdown, images, layoutTheme, null);
    }

    public static String markdownToHtml(String markdown,
                                        List<ArticleInlineImage> images,
                                        String layoutTheme,
                                        String category) {
        Theme theme = Theme.resolve(layoutTheme, category);
        StringBuilder body = new StringBuilder();
        Map<String, ArticleInlineImage> imagesBySection = new LinkedHashMap<>();
        if (images != null) {
            for (ArticleInlineImage image : images) {
                if (image != null && image.ready() && ContentTextUtils.hasText(image.getSectionTitle())) {
                    imagesBySection.put(normalizeSectionKey(image.getSectionTitle()), image);
                }
            }
        }
        boolean inUl = false;
        boolean inOl = false;
        String source = markdown == null ? "" : markdown;
        for (String rawLine : source.split("\\r?\\n")) {
            String line = rawLine == null ? "" : rawLine;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                closeLists(body, inUl, inOl);
                inUl = false;
                inOl = false;
                continue;
            }
            if (trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___")) {
                closeLists(body, inUl, inOl);
                inUl = false;
                inOl = false;
                body.append("<hr style=\"").append(theme.hr).append("\" />");
                continue;
            }
            if (trimmed.startsWith("### ")) {
                closeLists(body, inUl, inOl);
                inUl = false;
                inOl = false;
                body.append("<h3 style=\"").append(theme.h3).append("\">")
                        .append(renderInline(trimmed.substring(4).trim(), theme))
                        .append("</h3>");
                continue;
            }
            if (trimmed.startsWith("## ")) {
                closeLists(body, inUl, inOl);
                inUl = false;
                inOl = false;
                String sectionTitle = trimmed.substring(3).trim();
                body.append("<h2 style=\"").append(theme.h2).append("\">")
                        .append(renderInline(sectionTitle, theme))
                        .append("</h2>");
                ArticleInlineImage image = takeSectionImage(imagesBySection, sectionTitle);
                if (image != null) {
                    appendImage(body, image, theme);
                }
                continue;
            }
            if (trimmed.startsWith("# ")) {
                closeLists(body, inUl, inOl);
                inUl = false;
                inOl = false;
                // WeChat body title is usually the draft title; keep H1 styled as lead title.
                body.append("<h1 style=\"").append(theme.h1).append("\">")
                        .append(renderInline(trimmed.substring(2).trim(), theme))
                        .append("</h1>");
                continue;
            }
            if (trimmed.startsWith("> ")) {
                closeLists(body, inUl, inOl);
                inUl = false;
                inOl = false;
                body.append("<blockquote style=\"").append(theme.blockquote).append("\">")
                        .append("<p style=\"").append(theme.blockquoteP).append("\">")
                        .append(renderInline(trimmed.substring(2).trim(), theme))
                        .append("</p></blockquote>");
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                if (inOl) {
                    body.append("</ol>");
                    inOl = false;
                }
                if (!inUl) {
                    body.append("<ul style=\"").append(theme.ul).append("\">");
                    inUl = true;
                }
                String item = trimmed.startsWith("- ") ? trimmed.substring(2) : trimmed.substring(2);
                body.append("<li style=\"").append(theme.li).append("\">")
                        .append(renderInline(item.trim(), theme))
                        .append("</li>");
                continue;
            }
            Matcher ordered = ORDERED_ITEM.matcher(trimmed);
            if (ordered.matches()) {
                if (inUl) {
                    body.append("</ul>");
                    inUl = false;
                }
                if (!inOl) {
                    body.append("<ol style=\"").append(theme.ol).append("\">");
                    inOl = true;
                }
                body.append("<li style=\"").append(theme.li).append("\">")
                        .append(renderInline(ordered.group(1).trim(), theme))
                        .append("</li>");
                continue;
            }
            closeLists(body, inUl, inOl);
            inUl = false;
            inOl = false;
            body.append("<p style=\"").append(theme.p).append("\">")
                    .append(renderInline(trimmed, theme))
                    .append("</p>");
        }
        closeLists(body, inUl, inOl);

        // Place any leftover ready images before the closing section so they are not dropped.
        for (ArticleInlineImage leftover : imagesBySection.values()) {
            if (leftover != null && leftover.ready()) {
                appendImage(body, leftover, theme);
            }
        }

        return "<section data-content-factory=\"wechat\" data-layout=\""
                + escape(theme.id) + "\" style=\"" + theme.section + "\">\n"
                + body
                + "\n</section>";
    }

    private static void closeLists(StringBuilder html, boolean inUl, boolean inOl) {
        if (inUl) {
            html.append("</ul>");
        }
        if (inOl) {
            html.append("</ol>");
        }
    }

    static String renderInline(String text, Theme theme) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // Prefer **bold** over *em* when both could match; walk left-to-right.
        Matcher bold = BOLD.matcher(text);
        Matcher em = EMPHASIS.matcher(text);
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (cursor < text.length()) {
            bold.region(cursor, text.length());
            em.region(cursor, text.length());
            boolean boldFound = bold.find();
            boolean emFound = em.find();
            if (!boldFound && !emFound) {
                out.append(escape(text.substring(cursor)));
                break;
            }
            int boldStart = boldFound ? bold.start() : Integer.MAX_VALUE;
            int emStart = emFound ? em.start() : Integer.MAX_VALUE;
            if (boldFound && boldStart <= emStart) {
                out.append(escape(text.substring(cursor, bold.start())));
                out.append("<strong style=\"").append(theme.strong).append("\">")
                        .append(escape(bold.group(1)))
                        .append("</strong>");
                cursor = bold.end();
            } else {
                out.append(escape(text.substring(cursor, em.start())));
                out.append("<em style=\"").append(theme.em).append("\">")
                        .append(escape(em.group(1)))
                        .append("</em>");
                cursor = em.end();
            }
        }
        return out.toString();
    }

    private static void appendImage(StringBuilder html, ArticleInlineImage image, Theme theme) {
        html.append("<p style=\"").append(theme.imgWrap).append("\">")
                .append("<img src=\"").append(escape(image.renderUrl()))
                .append("\" alt=\"").append(escape(image.getAlt()))
                .append("\" style=\"").append(theme.img).append("\" />")
                .append("</p>");
        if (ContentTextUtils.hasText(image.getAttribution())) {
            html.append("<p style=\"").append(theme.caption).append("\">")
                    .append(escape(image.getAttribution()));
            if (ContentTextUtils.hasText(image.getLicense())) {
                html.append(" · ").append(escape(image.getLicense()));
            }
            html.append("</p>");
        }
    }

    private static ArticleInlineImage takeSectionImage(Map<String, ArticleInlineImage> imagesBySection,
                                                       String sectionTitle) {
        if (imagesBySection.isEmpty() || !ContentTextUtils.hasText(sectionTitle)) {
            return null;
        }
        String key = normalizeSectionKey(sectionTitle);
        ArticleInlineImage exact = imagesBySection.remove(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, ArticleInlineImage> entry : imagesBySection.entrySet()) {
            String candidate = entry.getKey();
            if (candidate.contains(key) || key.contains(candidate)) {
                return imagesBySection.remove(candidate);
            }
        }
        return null;
    }

    static String normalizeSectionKey(String title) {
        return ContentTextUtils.defaultText(title, "")
                .replaceAll("[\\s\\u3000]+", "")
                .replaceAll("[\\p{Punct}\\u3001\\u3002\\uff01\\uff1f\\uff1a\\uff1b\\u2014\\u2013\\u2026]+", "")
                .toLowerCase(Locale.ROOT);
    }

    /** Palette + layout variants for WeChat inline styles. */
    static final class Theme {
        final String id;
        final String section;
        final String h1;
        final String h2;
        final String h3;
        final String p;
        final String blockquote;
        final String blockquoteP;
        final String ul;
        final String ol;
        final String li;
        final String strong;
        final String em;
        final String img;
        final String imgWrap;
        final String caption;
        final String hr;

        private Theme(String id, String section, String h1, String h2, String h3, String p,
                      String blockquote, String blockquoteP, String ul, String ol, String li,
                      String strong, String em, String img, String imgWrap, String caption, String hr) {
            this.id = id;
            this.section = section;
            this.h1 = h1;
            this.h2 = h2;
            this.h3 = h3;
            this.p = p;
            this.blockquote = blockquote;
            this.blockquoteP = blockquoteP;
            this.ul = ul;
            this.ol = ol;
            this.li = li;
            this.strong = strong;
            this.em = em;
            this.img = img;
            this.imgWrap = imgWrap;
            this.caption = caption;
            this.hr = hr;
        }

        static Theme resolve(String layoutTheme, String category) {
            Palette palette = Palette.forCategory(category);
            String layout = normalizeLayout(layoutTheme);

            String sectionExtra;
            String h2Extra;
            String blockquoteExtra;
            switch (layout) {
                case "magazine":
                    sectionExtra = "letter-spacing:0;";
                    h2Extra = "padding:10px 12px;border-left:5px solid " + palette.color
                            + ";background:" + palette.softBg + ";border-radius:4px;";
                    blockquoteExtra = "border:1px solid " + palette.accent + ";";
                    break;
                case "warm":
                    sectionExtra = "background:" + palette.softBg + ";padding:18px 14px;border-radius:10px;letter-spacing:0;";
                    h2Extra = "padding-bottom:8px;border-bottom:2px solid " + palette.accent + ";";
                    blockquoteExtra = "";
                    break;
                case "clean":
                default:
                    layout = "clean";
                    sectionExtra = "letter-spacing:0;";
                    h2Extra = "padding-left:12px;border-left:4px solid " + palette.color + ";";
                    blockquoteExtra = "";
                    break;
            }

            String section = "box-sizing:border-box;max-width:100%;font-size:16px;line-height:1.85;"
                    + "color:#1f2937;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",\"PingFang SC\","
                    + "\"Hiragino Sans GB\",\"Microsoft YaHei\",sans-serif;" + sectionExtra;
            String h1 = "margin:0 0 18px;color:" + palette.color + ";font-size:22px;line-height:1.4;"
                    + "font-weight:700;text-align:center;letter-spacing:0.02em;";
            String h2 = "margin:28px 0 14px;color:" + palette.color + ";font-size:19px;line-height:1.45;"
                    + "font-weight:700;" + h2Extra;
            String h3 = "margin:22px 0 10px;color:#374151;font-size:17px;line-height:1.5;font-weight:700;";
            String p = "margin:0 0 16px;color:#1f2937;font-size:16px;line-height:1.85;text-align:justify;"
                    + "word-break:break-word;";
            String blockquote = "margin:18px 0;padding:14px 16px;border-left:4px solid " + palette.accent
                    + ";border-radius:8px;background:" + palette.softBg + ";color:#374151;" + blockquoteExtra;
            String blockquoteP = "margin:0;color:#374151;font-size:15px;line-height:1.75;";
            String ul = "margin:0 0 16px;padding-left:1.25em;color:#1f2937;";
            String ol = "margin:0 0 16px;padding-left:1.4em;color:#1f2937;";
            String li = "margin:0 0 8px;color:#1f2937;font-size:16px;line-height:1.75;";
            String strong = "color:" + palette.color + ";font-weight:700;";
            String em = "font-style:italic;color:#4b5563;";
            String img = "display:block;max-width:100%;width:100%;height:auto;margin:0 auto;border-radius:8px;";
            String imgWrap = "margin:20px 0 8px;text-align:center;line-height:0;";
            String caption = "margin:0 0 20px;color:#9ca3af;font-size:12px;line-height:1.5;text-align:center;";
            String hr = "height:1px;border:0;background:" + palette.accent + ";opacity:.4;margin:26px 0;";
            return new Theme(layout, section, h1, h2, h3, p, blockquote, blockquoteP,
                    ul, ol, li, strong, em, img, imgWrap, caption, hr);
        }

        private static String normalizeLayout(String value) {
            if (value == null) {
                return "clean";
            }
            String v = value.trim().toLowerCase(Locale.ROOT);
            if ("warm".equals(v) || "magazine".equals(v) || "clean".equals(v)) {
                return v;
            }
            return "clean";
        }
    }

    private static final class Palette {
        final String color;
        final String accent;
        final String softBg;

        private Palette(String color, String accent, String softBg) {
            this.color = color;
            this.accent = accent;
            this.softBg = softBg;
        }

        static Palette forCategory(String category) {
            if (category == null || category.isBlank()) {
                return TECH;
            }
            String c = category.trim().toLowerCase(Locale.ROOT);
            if (c.contains("emotion") || c.contains("心理") || c.contains("情感") || c.contains("关系")) {
                return EMOTION;
            }
            if (c.contains("history") || c.contains("philosoph") || c.contains("历史") || c.contains("哲学")) {
                return HISTORY;
            }
            if (c.contains("society") || c.contains("livelihood") || c.contains("社会") || c.contains("民生")) {
                return SOCIETY;
            }
            if (c.contains("科技") || c.contains("互联网") || c.contains("tech") || c.contains("ai")) {
                return TECH;
            }
            return TECH;
        }

        private static final Palette EMOTION = new Palette("#b8326f", "#f08ab8", "#fff4f8");
        private static final Palette HISTORY = new Palette("#8b5a2b", "#d4a574", "#faf6f1");
        private static final Palette SOCIETY = new Palette("#0f766e", "#5eead4", "#f0fdfa");
        private static final Palette TECH = new Palette("#1d4ed8", "#93c5fd", "#eff6ff");
    }
}
