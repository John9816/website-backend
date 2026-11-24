package com.example.website.module.reading.application;

import com.example.website.module.reading.api.dto.BookChapterListView;
import com.example.website.module.reading.api.dto.BookChapterView;
import com.example.website.module.reading.api.dto.BookContentView;
import com.example.website.module.reading.api.dto.BookSearchPageView;
import com.example.website.module.reading.api.dto.BookView;

import com.example.website.common.BusinessException;
import com.example.website.module.reading.domain.UserBookSource;
import com.example.website.module.reading.infrastructure.persistence.UserBookSourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes the deliberately small, CSS-only subset accepted by user imports. */
@Service
@RequiredArgsConstructor
public class ImportedBookSourceReader {
    /** Reserved user id used to store site-wide built-in book sources. */
    public static final long SYSTEM_USER_ID = 0L;
    private final UserBookSourceRepository repository;
    private final ObjectMapper objectMapper;
    private final OkHttpClient baseClient;

    public boolean supports(Long userId, String sourceId) {
        return find(userId, sourceId) != null;
    }

    /** Lists site-wide built-in sources that this engine can actually execute. */
    public List<UserBookSource> listSystemSources() {
        List<UserBookSource> result = new ArrayList<>();
        for (UserBookSource source : repository.findByUserIdOrderByCreatedAtDesc(SYSTEM_USER_ID)) {
            if (source.isEnabled() && "ready".equals(source.getStatus())) result.add(source);
        }
        return result;
    }

    public BookSearchPageView search(Long userId, String sourceId, String keyword, int page, int pageSize) {
        UserBookSource source = requireReady(userId, sourceId);
        JsonNode root = definition(source);
        String template = text(root, "searchUrl");
        if (template.isEmpty()) throw unsupported("书源缺少 searchUrl");
        // 标准书源 searchUrl 可能带 ,{"charset":"gbk"} 后缀，剥离
        int comma = template.indexOf(',');
        if (comma > 0 && template.charAt(comma + 1) == '{') template = template.substring(0, comma);
        String url = template.replace("{{key}}", encode(keyword)).replace("{{page}}", String.valueOf(page));
        Fetched fetched = fetch(source, url);
        JsonNode rules = rules(root, "ruleSearch");
        Elements rows = select(fetched.document, text(rules, "bookList"));
        List<BookView> books = new ArrayList<>();
        for (Element row : rows) {
            String name = extract(row, text(rules, "bookName"), fetched.url);
            String bookUrl = absolute(fetched.url, extract(row, text(rules, "bookUrl"), fetched.url));
            if (name.isEmpty() || bookUrl.isEmpty()) continue;
            books.add(new BookView(source.getSourceKey(), source.getSourceName(), name,
                    extract(row, text(rules, "bookAuthor"), fetched.url),
                    absolute(fetched.url, extract(row, text(rules, "bookCover"), fetched.url)), bookUrl,
                    extract(row, text(rules, "bookIntro"), fetched.url), "", "",
                    extract(row, text(rules, "bookKind"), fetched.url)));
            if (books.size() >= pageSize) break;
        }
        return new BookSearchPageView(books, page, pageSize, books.size(), rows.size() > pageSize);
    }

    public BookView detail(Long userId, String sourceId, String bookUrl) {
        UserBookSource source = requireReady(userId, sourceId);
        Fetched fetched = fetch(source, bookUrl);
        JsonNode rules = rules(definition(source), "ruleBookInfo");
        String name = extract(fetched.document, text(rules, "name"), fetched.url);
        String author = extract(fetched.document, text(rules, "author"), fetched.url);
        String cover = absolute(fetched.url, extract(fetched.document, text(rules, "coverUrl"), fetched.url));
        String intro = extract(fetched.document, text(rules, "intro"), fetched.url);
        String category = extract(fetched.document, text(rules, "category"), fetched.url);
        return new BookView(source.getSourceKey(), source.getSourceName(), name, author, cover,
                fetched.url, intro, extract(fetched.document, text(rules, "lastChapter"), fetched.url),
                extract(fetched.document, text(rules, "status"), fetched.url), category);
    }

    public BookChapterListView chapters(Long userId, String sourceId, String bookUrl) {
        UserBookSource source = requireReady(userId, sourceId);
        Fetched fetched = fetch(source, bookUrl);
        JsonNode rules = rules(definition(source), "ruleToc");
        Elements rows = select(fetched.document, text(rules, "chapterList"));
        List<BookChapterView> chapters = new ArrayList<>();
        int index = 0;
        for (Element row : rows) {
            String title = extract(row, text(rules, "chapterName"), fetched.url);
            String url = absolute(fetched.url, extract(row, text(rules, "chapterUrl"), fetched.url));
            if (!title.isEmpty() && !url.isEmpty()) chapters.add(new BookChapterView(title, url, index++, ""));
            if (chapters.size() >= 10000) break;
        }
        return new BookChapterListView(chapters, chapters.size());
    }

    public BookContentView content(Long userId, String sourceId, String bookUrl, String chapterUrl) {
        UserBookSource source = requireReady(userId, sourceId);
        Fetched fetched = fetch(source, chapterUrl);
        JsonNode rules = rules(definition(source), "ruleContent");
        List<Element> contentNodes = resolve(fetched.document, text(rules, "content"));
        Element content = contentNodes.isEmpty() ? fetched.document.body() : contentNodes.get(0);
        StringBuilder out = new StringBuilder();
        append(content, out);
        return new BookContentView(extract(fetched.document, text(rules, "title"), fetched.url),
                out.toString().replaceAll("\\n{3,}", "\\n\\n").trim(), fetched.url);
    }

    private UserBookSource requireReady(Long userId, String sourceId) {
        UserBookSource source = find(userId, sourceId);
        if (source == null) throw new BusinessException(404, "书源不存在");
        if (!source.isEnabled() || !"ready".equals(source.getStatus())) throw unsupported(source.getMessage());
        return source;
    }

    private UserBookSource find(Long userId, String sourceId) {
        if (sourceId == null || sourceId.trim().isEmpty()) return null;
        String key = sourceId.trim();
        // A caller's own source always wins, so users can override a built-in source.
        if (userId != null) {
            UserBookSource own = repository.findByUserIdAndSourceKey(userId, key).orElse(null);
            if (own != null) return own;
        }
        // Fall back to site-wide built-in sources seeded with the reserved system user id.
        return repository.findByUserIdAndSourceKey(SYSTEM_USER_ID, key).orElse(null);
    }

    private JsonNode definition(UserBookSource source) {
        try { return objectMapper.readTree(source.getDefinitionJson()); }
        catch (Exception ex) { throw unsupported("书源配置无法读取"); }
    }

    /** 规则字段既可能是原生对象（内置源），也可能是二次编码的 JSON 字符串（标准阅读书源导入）。 */
    private JsonNode rules(JsonNode root, String key) {
        JsonNode node = root.get(key);
        if (node == null || node.isNull()) return objectMapper.createObjectNode();
        if (node.isTextual()) {
            try {
                JsonNode parsed = objectMapper.readTree(node.asText());
                if (parsed != null && parsed.isObject()) return parsed;
            } catch (Exception ignored) {}
            return objectMapper.createObjectNode();
        }
        return node;
    }

    private static final int MAX_REDIRECTS = 4;

    private Fetched fetch(UserBookSource source, String rawUrl) {
        HttpUrl base = HttpUrl.parse(source.getSourceUrl());
        if (base == null) throw new BusinessException("书源地址无效");
        HttpUrl url = base.resolve(rawUrl);
        if (url == null || !isPublicHttp(url)) throw new BusinessException("不允许访问该书源地址");
        // 手动跟随重定向：小说站常跨域名跳转换域名（如 b5200.net -> bqg6.xyz）。
        // 每跳都校验为公网 http(s)，拦截内网地址以防 SSRF，最多 4 跳。
        okhttp3.OkHttpClient client = baseClient.newBuilder().followRedirects(false).followSslRedirects(false).build();
        HttpUrl current = url;
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                if (!isPublicHttp(current)) throw new BusinessException("书源重定向到不允许的地址");
                Request request = new Request.Builder().url(current)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 8.1.0; AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                        .header("Accept-Language", "zh-CN,zh;q=0.9").get().build();
                try (okhttp3.Response response = client.newCall(request).execute()) {
                    int code = response.code();
                    if (code >= 300 && code < 400 && response.header("Location") != null) {
                        HttpUrl next = current.resolve(response.header("Location"));
                        if (next == null || !isPublicHttp(next)) throw new BusinessException("书源重定向到不允许的地址");
                        current = next;
                        continue;
                    }
                    if (!response.isSuccessful() || response.body() == null) throw new BusinessException(503, "自定义书源暂时无法访问");
                    String html = response.body().string();
                    return new Fetched(html, current.toString(), Jsoup.parse(html, current.toString()));
                }
            }
            throw new BusinessException(503, "书源重定向次数过多");
        } catch (IOException ex) { throw new BusinessException(503, "自定义书源请求失败"); }
    }

    /** 仅允许公网 http(s)，拒绝内网/回环/链路本地地址，防 SSRF。 */
    private boolean isPublicHttp(HttpUrl url) {
        String scheme = url.scheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        String host = url.host().toLowerCase();
        if (host.equals("localhost") || host.endsWith(".localhost") || host.equals("0.0.0.0")) return false;
        if (host.matches("127\\.\\d+\\.\\d+\\.\\d+") || host.matches("10\\.\\d+\\.\\d+\\.\\d+")
                || host.matches("192\\.168\\.\\d+\\.\\d+") || host.matches("169\\.254\\.\\d+\\.\\d+")
                || host.matches("172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+")) return false;
        if (host.equals("::1") || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")) return false;
        return true;
    }

    // ---------- 标准阅读书源 DSL 求值器 ----------
    // 支持: class.x / id.x / tag.x / 原生CSS, .N 索引, @ 链式,
    //       @text/@textNodes/@html/@href/@src/@content, !a:b:c 多索引, ##正则##替换, || 回退
    private static final Pattern DSL_INDEX = Pattern.compile("\\.(\\d+)$");
    private static final Pattern DSL_REPLACE = Pattern.compile("##(.+?)##(.*)");

    private List<Element> resolve(Element root, String expression) {
        if (expression == null || expression.trim().isEmpty()) return new ArrayList<>();
        String expr = expression.trim();
        // 剥离 ##正则##替换 后缀（只影响取值，不影响节点选择）
        int hash = expr.indexOf("##");
        if (hash >= 0) expr = expr.substring(0, hash).trim();
        // || 回退：取第一个能选出结果的分支
        String[] alternatives = expr.split("\\|\\|");
        for (String alternative : alternatives) {
            List<Element> nodes = resolveChain(root, alternative.trim());
            if (!nodes.isEmpty()) return nodes;
        }
        return new ArrayList<>();
    }

    private List<Element> resolveChain(Element root, String chain) {
        List<Element> current = new ArrayList<>();
        current.add(root);
        for (String tokenRaw : chain.split("@")) {
            String token = tokenRaw.trim();
            if (token.isEmpty()) continue;
            // 先剥离 ##正则##替换 后缀（只作用于取值，不影响选择）
            int hash = token.indexOf("##");
            if (hash >= 0) token = token.substring(0, hash).trim();
            if (token.isEmpty() || isValueOp(token)) continue; // 取值指令，非选择器
            if (current.isEmpty()) break;
            List<Integer> indices = null;
            // 多索引 !a:b:c
            int bang = token.indexOf('!');
            if (bang >= 0) {
                indices = parseIndices(token.substring(bang + 1));
                token = token.substring(0, bang);
            }
            // 单索引 .N（放在前缀转换之后判断，避免误伤 .book 类名）
            Matcher idx = DSL_INDEX.matcher(token);
            if (indices == null && idx.find()) {
                indices = parseIndices(idx.group(1));
                token = token.substring(0, idx.start());
            }
            String css = toCss(token);
            if (css.isEmpty()) continue;
            List<Element> next = new ArrayList<>();
            LinkedHashSet<Element> dedup = new LinkedHashSet<>();
            for (Element cur : current) {
                Elements found;
                try { found = cur.select(css); } catch (Exception ex) { continue; }
                if (indices == null) {
                    for (Element e : found) dedup.add(e);
                } else {
                    for (int i : indices) if (i >= 0 && i < found.size()) dedup.add(found.get(i));
                }
            }
            next.addAll(dedup);
            current = next;
        }
        return current;
    }

    /** 取值指令不参与节点选择。 */
    private boolean isValueOp(String token) {
        if (token == null) return false;
        String t = token.toLowerCase();
        return t.equals("text") || t.equals("textnodes") || t.equals("html") || t.equals("texts")
                || t.equals("owntext") || t.equals("href") || t.equals("src") || t.equals("content")
                || t.equals("all");
    }

    private List<Integer> parseIndices(String raw) {
        List<Integer> result = new ArrayList<>();
        for (String part : raw.split(":")) {
            try { result.add(Integer.parseInt(part.trim())); } catch (Exception ignored) {}
        }
        return result;
    }

    private String toCss(String token) {
        String value = token.trim();
        if (value.isEmpty()) return "";
        if (value.startsWith("class.")) return "." + value.substring(6).trim();
        if (value.startsWith("id.")) return "#" + value.substring(3).trim();
        if (value.startsWith("tag.")) return value.substring(4).trim();
        return value; // 已是原生选择器
    }

    private Elements select(Element root, String rule) {
        return new Elements(resolve(root, rule));
    }

    private String extract(Element root, String rule, String baseUrl) {
        if (rule == null || rule.trim().isEmpty()) return "";
        String expression = rule.trim();
        // 取值指令：优先用显式 @ 指令（剥离 ## 后缀），否则默认 text
        String op = "text";
        String lastToken = expression.contains("@")
                ? expression.substring(expression.lastIndexOf('@') + 1).trim() : "";
        int hash = lastToken.indexOf("##");
        if (hash >= 0) lastToken = lastToken.substring(0, hash).trim();
        for (String candidate : new String[]{"textNodes", "texts", "ownText", "text", "html", "href", "src", "content", "all"}) {
            if (candidate.equalsIgnoreCase(lastToken)) { op = candidate.toLowerCase(); break; }
        }
        List<Element> nodes = resolve(root, expression);
        if (nodes.isEmpty()) return "";
        boolean joinAll = "textnodes".equals(op) || "texts".equals(op) || "all".equals(op);
        StringBuilder out = new StringBuilder();
        // 单值规则只取第一个命中节点；textNodes/texts/all 由 joinTextNodes 一次性拼接全部
        List<Element> targets = java.util.Collections.singletonList(nodes.get(0));
        for (Element element : targets) {
            String value;
            switch (op) {
                case "html": value = element.html(); break;
                case "href": value = element.attr("href"); break;
                case "src": value = element.attr("src"); break;
                case "content": value = element.attr("content"); break;
                case "textnodes":
                case "texts":
                case "all":
                    value = joinAll ? joinTextNodes(nodes) : element.text(); break;
                case "owntext": value = element.ownText(); break;
                default: value = element.text(); break;
            }
            if (!value.trim().isEmpty()) {
                if (out.length() > 0) out.append('\n');
                out.append(value);
            }
        }
        String result = out.toString();
        // 应用 ##正则##替换
        Matcher repl = DSL_REPLACE.matcher(rule);
        if (repl.find()) {
            try { result = result.replaceAll(repl.group(1), repl.group(2) == null ? "" : repl.group(2)); }
            catch (Exception ignored) {}
        }
        return result.replaceAll("\\n{3,}", "\\n\\n").trim();
    }

    private String textNodes(Element element) {
        StringBuilder out = new StringBuilder();
        for (Node child : element.childNodes()) {
            if (child instanceof TextNode) out.append(((TextNode) child).getWholeText());
            else if (child instanceof Element && "br".equals(((Element) child).tagName())) out.append('\n');
        }
        return out.toString().trim();
    }

    private String joinTextNodes(List<Element> nodes) {
        StringBuilder out = new StringBuilder();
        for (Element element : nodes) {
            String chunk = textNodes(element);
            if (chunk.isEmpty()) chunk = element.text();
            if (!chunk.isEmpty()) {
                if (out.length() > 0) out.append('\n');
                out.append(chunk);
            }
        }
        return out.toString();
    }
    private String absolute(String baseUrl, String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        HttpUrl base = HttpUrl.parse(baseUrl);
        HttpUrl url = base == null ? null : base.resolve(raw.trim());
        return url != null && isPublicHttp(url) ? url.toString() : "";
    }
    private String text(JsonNode node, String key) { JsonNode value = node.get(key); return value == null || value.isNull() ? "" : value.asText("").trim(); }
    private String encode(String value) {
        try { return URLEncoder.encode(value == null ? "" : value.trim(), "UTF-8"); }
        catch (Exception ex) { throw new BusinessException("搜索关键词编码失败"); }
    }
    private BusinessException unsupported(String message) { return new BusinessException(400, message == null || message.isEmpty() ? "该书源规则暂不支持" : message); }
    private void append(Node node, StringBuilder out) {
        if (node instanceof TextNode) { out.append(((TextNode) node).getWholeText()); return; }
        if (node instanceof Element && "br".equals(((Element) node).tagName())) { out.append('\n'); return; }
        for (Node child : node.childNodes()) append(child, out);
        if (node instanceof Element && ("p".equals(((Element) node).tagName()) || "div".equals(((Element) node).tagName()) || "li".equals(((Element) node).tagName()))) out.append('\n');
    }
    private static final class Fetched { final String body; final String url; final Document document; Fetched(String body, String url, Document document) { this.body = body; this.url = url; this.document = document; } }
}
