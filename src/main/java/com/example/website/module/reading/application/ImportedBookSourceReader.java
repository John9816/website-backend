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
import java.util.List;

/** Executes the deliberately small, CSS-only subset accepted by user imports. */
@Service
@RequiredArgsConstructor
public class ImportedBookSourceReader {
    private final UserBookSourceRepository repository;
    private final ObjectMapper objectMapper;
    private final OkHttpClient baseClient;

    public boolean supports(Long userId, String sourceId) {
        return find(userId, sourceId) != null;
    }

    public BookSearchPageView search(Long userId, String sourceId, String keyword, int page, int pageSize) {
        UserBookSource source = requireReady(userId, sourceId);
        JsonNode root = definition(source);
        String template = text(root, "searchUrl");
        if (template.isEmpty()) throw unsupported("书源缺少 searchUrl");
        String url = template.replace("{{key}}", encode(keyword)).replace("{{page}}", String.valueOf(page));
        Fetched fetched = fetch(source, url);
        JsonNode rules = root.path("ruleSearch");
        Elements rows = select(fetched.document, text(rules, "bookList"));
        List<BookView> books = new ArrayList<>();
        for (Element row : rows) {
            String name = extract(row, text(rules, "bookName"), fetched.url);
            String bookUrl = absolute(source, extract(row, text(rules, "bookUrl"), fetched.url));
            if (name.isEmpty() || bookUrl.isEmpty()) continue;
            books.add(new BookView(source.getSourceKey(), source.getSourceName(), name,
                    extract(row, text(rules, "bookAuthor"), fetched.url),
                    absolute(source, extract(row, text(rules, "bookCover"), fetched.url)), bookUrl,
                    extract(row, text(rules, "bookIntro"), fetched.url), "", "",
                    extract(row, text(rules, "bookKind"), fetched.url)));
            if (books.size() >= pageSize) break;
        }
        return new BookSearchPageView(books, page, pageSize, books.size(), rows.size() > pageSize);
    }

    public BookView detail(Long userId, String sourceId, String bookUrl) {
        UserBookSource source = requireReady(userId, sourceId);
        Fetched fetched = fetch(source, bookUrl);
        JsonNode rules = definition(source).path("ruleBookInfo");
        String name = extract(fetched.document, text(rules, "name"), fetched.url);
        String author = extract(fetched.document, text(rules, "author"), fetched.url);
        String cover = absolute(source, extract(fetched.document, text(rules, "coverUrl"), fetched.url));
        String intro = extract(fetched.document, text(rules, "intro"), fetched.url);
        String category = extract(fetched.document, text(rules, "category"), fetched.url);
        return new BookView(source.getSourceKey(), source.getSourceName(), name, author, cover,
                fetched.url, intro, extract(fetched.document, text(rules, "lastChapter"), fetched.url),
                extract(fetched.document, text(rules, "status"), fetched.url), category);
    }

    public BookChapterListView chapters(Long userId, String sourceId, String bookUrl) {
        UserBookSource source = requireReady(userId, sourceId);
        Fetched fetched = fetch(source, bookUrl);
        JsonNode rules = definition(source).path("ruleToc");
        Elements rows = select(fetched.document, text(rules, "chapterList"));
        List<BookChapterView> chapters = new ArrayList<>();
        int index = 0;
        for (Element row : rows) {
            String title = extract(row, text(rules, "chapterName"), fetched.url);
            String url = absolute(source, extract(row, text(rules, "chapterUrl"), fetched.url));
            if (!title.isEmpty() && !url.isEmpty()) chapters.add(new BookChapterView(title, url, index++, ""));
            if (chapters.size() >= 10000) break;
        }
        return new BookChapterListView(chapters, chapters.size());
    }

    public BookContentView content(Long userId, String sourceId, String bookUrl, String chapterUrl) {
        UserBookSource source = requireReady(userId, sourceId);
        Fetched fetched = fetch(source, chapterUrl);
        JsonNode rules = definition(source).path("ruleContent");
        Element content = fetched.document.selectFirst(text(rules, "content"));
        if (content == null) content = fetched.document.body();
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
        if (userId == null || sourceId == null || sourceId.trim().isEmpty()) return null;
        return repository.findByUserIdAndSourceKey(userId, sourceId.trim()).orElse(null);
    }

    private JsonNode definition(UserBookSource source) {
        try { return objectMapper.readTree(source.getDefinitionJson()); }
        catch (Exception ex) { throw unsupported("书源配置无法读取"); }
    }

    private Fetched fetch(UserBookSource source, String rawUrl) {
        HttpUrl base = HttpUrl.parse(source.getSourceUrl());
        HttpUrl url = base == null ? null : base.resolve(rawUrl);
        if (url == null || !url.host().equalsIgnoreCase(base.host()) || !(url.scheme().equals("http") || url.scheme().equals("https"))) throw new BusinessException("不允许访问该书源地址");
        Request request = new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 DuckMusic Reader").get().build();
        try (okhttp3.Response response = baseClient.newBuilder().followRedirects(false).build().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new BusinessException(503, "自定义书源暂时无法访问");
            HttpUrl finalUrl = response.request().url();
            if (!finalUrl.host().equalsIgnoreCase(base.host())) throw new BusinessException("书源响应地址不在允许域名内");
            String html = response.body().string();
            return new Fetched(html, finalUrl.toString(), Jsoup.parse(html, finalUrl.toString()));
        } catch (IOException ex) { throw new BusinessException(503, "自定义书源请求失败"); }
    }

    private Elements select(Element root, String rule) {
        if (rule == null || rule.trim().isEmpty()) return new Elements();
        String selector = selector(rule);
        try { return root.select(selector); } catch (Exception ex) { throw new BusinessException("书源 CSS 选择器无效"); }
    }

    private String extract(Element root, String rule, String baseUrl) {
        if (rule == null || rule.trim().isEmpty()) return "";
        String expression = rule.split("\\|\\|")[0].trim();
        String[] parts = expression.split("@", 2);
        Element element = root.selectFirst(selector(parts[0]));
        if (element == null) return "";
        if (parts.length == 1 || "text".equalsIgnoreCase(parts[1])) return element.text().trim();
        String attr = parts[1].trim();
        if ("html".equalsIgnoreCase(attr)) return element.html();
        if ("href".equalsIgnoreCase(attr) || "src".equalsIgnoreCase(attr) || "content".equalsIgnoreCase(attr)) return element.attr(attr);
        return element.attr(attr);
    }

    private String selector(String value) { return value.replace("@text", "").trim(); }
    private String absolute(UserBookSource source, String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        HttpUrl base = HttpUrl.parse(source.getSourceUrl());
        HttpUrl url = base == null ? null : base.resolve(raw.trim());
        return url != null && url.host().equalsIgnoreCase(base.host()) ? url.toString() : "";
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
