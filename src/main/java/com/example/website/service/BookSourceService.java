package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.book.BookChapterListView;
import com.example.website.dto.book.BookChapterView;
import com.example.website.dto.book.BookContentView;
import com.example.website.dto.book.BookSearchPageView;
import com.example.website.dto.book.BookSourceCatalogView;
import com.example.website.dto.book.BookView;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controlled Legado-compatible reader chain.
 *
 * <p>The upstream source contains JavaScript rules. They are deliberately not
 * executed here. The currently enabled source (7630, Pinshuzhai) is mapped to
 * equivalent fixed Jsoup selectors and a DOM cleaner, which keeps the service
 * deterministic and prevents a remote source from executing code on the
 * backend.</p>
 */
@Slf4j
@Service
public class BookSourceService {

    public static final String PINSHUZHAI_SOURCE_ID = "7630";
    private static final String PINSHUZHAI_SOURCE_NAME = "品书斋";
    private static final String PINSHUZHAI_BASE_URL = "https://www.pinshuzhai.com";
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_CHAPTERS = 10000;
    private static final Pattern COUNT_PATTERN = Pattern.compile("(\\d+)");
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 8.1.0) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36";

    private final OkHttpClient httpClient;

    @Autowired
    public BookSourceService(OkHttpClient httpClient) {
        this.httpClient = httpClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(9, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    public List<BookSourceCatalogView> listCatalogs() {
        return Arrays.asList(
                new BookSourceCatalogView(
                        PINSHUZHAI_SOURCE_ID,
                        PINSHUZHAI_SOURCE_NAME,
                        "book",
                        "已接入搜索、详情、目录和正文；仅执行固定 Jsoup 规则。",
                        "https://www.yckceo.com/yuedu/shuyuan/json/id/7630.json",
                        null,
                        true,
                        "ready",
                        true),
                new BookSourceCatalogView(
                        "yckceo-rss",
                        "YckCeo 订阅源",
                        "rss",
                        "文章、资讯和其他订阅内容，当前后端不执行 RSS/Legado 规则。",
                        "https://www.yckceo.com/yuedu/rss/index.html",
                        null,
                        true,
                        "catalog-only",
                        false),
                new BookSourceCatalogView(
                        "lifves-legacy",
                        "Lifves 旧书源",
                        "book",
                        "历史书源归档，当前后端不执行其中的动态规则。",
                        "https://www.lifves.com/Home/book/booksourcelist.html",
                        "https://www.lifves.com/api/v2/booksource/list",
                        false,
                        "unsupported",
                        false)
        );
    }

    public BookSearchPageView search(String sourceId, String keyword, int page, int pageSize) {
        requireSource(sourceId);
        String normalizedKeyword = requireKeyword(keyword);
        int safePage = clamp(page, 1, 1000);
        int safePageSize = clamp(pageSize, 1, 50);
        HttpUrl base = sourceBaseUrl();
        HttpUrl url = base.newBuilder()
                .encodedPath("/page/" + safePage)
                .addQueryParameter("s", normalizedKeyword)
                .build();
        FetchedPage fetched = fetch(url);
        return parseSearchHtml(fetched.body, fetched.url.toString(), safePage, safePageSize);
    }

    public BookView detail(String sourceId, String bookUrl) {
        requireSource(sourceId);
        HttpUrl url = checkedUrl(bookUrl, "书籍地址");
        FetchedPage fetched = fetch(url);
        return parseDetailHtml(fetched.body, fetched.url.toString(), fetched.url.toString());
    }

    public BookChapterListView chapters(String sourceId, String bookUrl) {
        requireSource(sourceId);
        HttpUrl url = checkedUrl(bookUrl, "书籍地址");
        FetchedPage fetched = fetch(url);
        return parseChaptersHtml(fetched.body, fetched.url.toString());
    }

    public BookContentView content(String sourceId, String bookUrl, String chapterUrl) {
        requireSource(sourceId);
        // Keep bookUrl in the contract and validate it as well. This prevents
        // clients from using this endpoint as a generic URL proxy.
        checkedUrl(bookUrl, "书籍地址");
        HttpUrl url = checkedUrl(chapterUrl, "章节地址");
        FetchedPage fetched = fetch(url);
        return parseContentHtml(fetched.body, fetched.url.toString(), fetched.url.toString());
    }

    /** Package-private parser entry points keep tests independent of the live site. */
    BookSearchPageView parseSearchHtml(String html, String baseUrl, int page, int pageSize) {
        Document doc = Jsoup.parse(html == null ? "" : html, baseUrl);
        Elements rows = doc.select(".catdanlist ul li");
        List<BookView> books = new ArrayList<>();
        for (Element row : rows) {
            Element titleLink = row.selectFirst(".bookinfo h4 a");
            if (titleLink == null) continue;
            String bookUrl = absoluteAllowedUrl(titleLink.attr("href"), baseUrl);
            if (bookUrl == null) continue;
            String name = clean(titleLink.text());
            if (name.isEmpty()) continue;
            String author = clean(firstText(row, ".bookinfo .author a"));
            String intro = clean(firstText(row, ".bookinfo p:last-of-type"));
            String category = joinTexts(row.select(".bookinfo .cats a"));
            String coverUrl = absoluteAllowedUrl(firstAttribute(row, "img", "src"), baseUrl);
            books.add(new BookView(PINSHUZHAI_SOURCE_ID, PINSHUZHAI_SOURCE_NAME,
                    name, author, coverUrl, bookUrl, intro, "", "", category));
        }

        int total = parseTotal(doc, books.size());
        boolean hasNextLink = hasNextPage(doc, page);
        boolean hasMore = hasNextLink || (page * Math.max(books.size(), 1) < total);
        if (books.size() > pageSize) {
            books = new ArrayList<>(books.subList(0, pageSize));
            hasMore = true;
        }
        return new BookSearchPageView(books, page, pageSize, total, hasMore);
    }

    BookView parseDetailHtml(String html, String baseUrl, String bookUrl) {
        Document doc = Jsoup.parse(html == null ? "" : html, baseUrl);
        String name = meta(doc, "og:novel:book_name");
        if (name.isEmpty()) name = clean(firstText(doc, "h2.zz"));
        String author = meta(doc, "og:novel:author");
        if (author.isEmpty()) author = clean(firstText(doc, "h2.zz span"));
        String cover = meta(doc, "og:image");
        if (cover.isEmpty()) cover = firstAttribute(doc, ".book-box-image", "data-src");
        cover = absoluteAllowedUrl(cover, baseUrl);
        String intro = cleanDescription(doc.selectFirst(".docspt"));
        String category = joinTexts(doc.select(".catb a"));
        String latestChapter = meta(doc, "og:novel:latest_chapter_name");
        String status = meta(doc, "og:novel:status");
        return new BookView(PINSHUZHAI_SOURCE_ID, PINSHUZHAI_SOURCE_NAME,
                name, author, cover, bookUrl, intro, latestChapter, status, category);
    }

    BookChapterListView parseChaptersHtml(String html, String baseUrl) {
        Document doc = Jsoup.parse(html == null ? "" : html, baseUrl);
        Elements anchors = doc.select(".newzxzj .zjlist a");
        List<BookChapterView> chapters = new ArrayList<>();
        int index = 0;
        for (Element anchor : anchors) {
            if (chapters.size() >= MAX_CHAPTERS) break;
            String url = absoluteAllowedUrl(anchor.attr("href"), baseUrl);
            String title = clean(anchor.text());
            if (url == null || title.isEmpty()) continue;
            chapters.add(new BookChapterView(title, url, index++, ""));
        }
        return new BookChapterListView(chapters, chapters.size());
    }

    BookContentView parseContentHtml(String html, String baseUrl, String chapterUrl) {
        Document doc = Jsoup.parse(html == null ? "" : html, baseUrl);
        Element chapter = doc.selectFirst("#chapter");
        if (chapter == null) chapter = doc.body();
        if (chapter == null) {
            return new BookContentView("", "", chapterUrl);
        }
        String title = clean(firstText(chapter, "h2"));
        Element content = chapter.selectFirst(".readcon, .dubox, .yd_text, .content");
        if (content == null) content = chapter;
        content.select(".hid, .ydgong, .tjlm, .rdinfo, script, style, noscript, h1, h2, h3").remove();
        StringBuilder raw = new StringBuilder();
        appendText(content, raw);
        String text = normalizeContent(raw.toString());
        return new BookContentView(title, text, chapterUrl);
    }

    private void appendText(Node node, StringBuilder out) {
        if (node instanceof TextNode) {
            out.append(((TextNode) node).getWholeText());
            return;
        }
        if (node instanceof Element) {
            String tag = ((Element) node).tagName().toLowerCase(Locale.ROOT);
            if ("br".equals(tag)) {
                out.append('\n');
                return;
            }
            for (Node child : node.childNodes()) appendText(child, out);
            if ("p".equals(tag) || "div".equals(tag) || "li".equals(tag)) out.append('\n');
            return;
        }
        for (Node child : node.childNodes()) appendText(child, out);
    }

    private String normalizeContent(String value) {
        String[] lines = value.replace('\r', '\n').replace('\u00a0', ' ').split("\\n");
        StringBuilder result = new StringBuilder();
        int emptyLines = 0;
        for (String line : lines) {
            String trimmed = clean(line);
            if (trimmed.isEmpty() || isSourceMarker(trimmed)) {
                if (result.length() > 0 && emptyLines < 2) {
                    result.append('\n');
                    emptyLines++;
                }
                continue;
            }
            if (result.length() > 0 && emptyLines == 0) result.append('\n');
            result.append(trimmed);
            emptyLines = 0;
        }
        return result.toString().trim();
    }

    private boolean isSourceMarker(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("pinshuzhai.com") || text.contains("品书斋");
    }

    private String cleanDescription(Element docspt) {
        if (docspt == null) return "";
        Element copy = docspt.clone();
        copy.select("div, span, strong, b").forEach(label -> {
            if (label.text().trim().matches("作品简介\\s*[:：]?")) label.remove();
        });
        return clean(copy.text()).replaceFirst("^作品简介\\s*[:：]?\\s*", "").trim();
    }

    private boolean hasNextPage(Document doc, int page) {
        String expected = "/page/" + (page + 1);
        for (Element link : doc.select(".pagination a[href]")) {
            String href = link.attr("href");
            if (href.contains(expected)) return true;
        }
        return false;
    }

    private int parseTotal(Document doc, int fallback) {
        String text = firstText(doc, ".bookuplist h3 span span");
        if (text.isEmpty()) text = firstText(doc, ".cattongji span span");
        Matcher matcher = COUNT_PATTERN.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private String meta(Document doc, String property) {
        for (Element element : doc.select("meta[property]")) {
            if (property.equalsIgnoreCase(element.attr("property"))) {
                return clean(element.attr("content"));
            }
        }
        return "";
    }

    private String firstText(Element root, String selector) {
        Element element = root == null ? null : root.selectFirst(selector);
        return element == null ? "" : element.text();
    }

    private String firstText(Document root, String selector) {
        Element element = root == null ? null : root.selectFirst(selector);
        return element == null ? "" : element.text();
    }

    private String firstAttribute(Element root, String selector, String attribute) {
        Element element = root == null ? null : root.selectFirst(selector);
        return element == null ? "" : element.attr(attribute);
    }

    private String firstAttribute(Document root, String selector, String attribute) {
        Element element = root == null ? null : root.selectFirst(selector);
        return element == null ? "" : element.attr(attribute);
    }

    private String joinTexts(Elements elements) {
        if (elements == null || elements.isEmpty()) return "";
        List<String> values = new ArrayList<>();
        for (Element element : elements) {
            String value = clean(element.text());
            if (!value.isEmpty()) values.add(value);
        }
        return join(values, " · ");
    }

    private String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }

    private String clean(String value) {
        if (value == null) return "";
        return value.replace('\u00a0', ' ').replaceAll("[ \\t\\f\\v]+", " ").trim();
    }

    private String requireKeyword(String keyword) {
        String value = clean(keyword);
        if (value.isEmpty()) throw new BusinessException("请输入书名或作者");
        if (value.length() > 100) throw new BusinessException("搜索关键词过长");
        return value;
    }

    private void requireSource(String sourceId) {
        if (sourceId == null || sourceId.trim().isEmpty()
                || PINSHUZHAI_SOURCE_ID.equals(sourceId.trim())) return;
        throw new BusinessException(404, "暂不支持该书源");
    }

    private HttpUrl sourceBaseUrl() {
        HttpUrl base = HttpUrl.parse(PINSHUZHAI_BASE_URL);
        if (base == null || base.host() == null
                || !("http".equals(base.scheme()) || "https".equals(base.scheme()))) {
            throw new BusinessException(503, "品书斋书源配置无效");
        }
        return base;
    }

    private HttpUrl checkedUrl(String raw, String label) {
        HttpUrl url = HttpUrl.parse(raw == null ? "" : raw.trim());
        if (url == null) throw new BusinessException("无效的" + label);
        return checkedUrl(url, label);
    }

    private HttpUrl checkedUrl(HttpUrl url, String label) {
        HttpUrl base = sourceBaseUrl();
        Set<String> hosts = new HashSet<>();
        String host = base.host().toLowerCase(Locale.ROOT);
        hosts.add(host);
        if (host.startsWith("www.")) hosts.add(host.substring(4));
        else hosts.add("www." + host);
        boolean allowedScheme = base.scheme().equalsIgnoreCase(url.scheme());
        if (!allowedScheme || !hosts.contains(url.host().toLowerCase(Locale.ROOT))) {
            throw new BusinessException("不允许访问该" + label);
        }
        return url;
    }

    private String absoluteAllowedUrl(String raw, String baseUrl) {
        if (raw == null || raw.trim().isEmpty()) return null;
        HttpUrl base = HttpUrl.parse(baseUrl);
        HttpUrl candidate = base == null ? null : base.resolve(raw.trim());
        if (candidate == null) return null;
        try {
            return checkedUrl(candidate, "书源链接").toString();
        } catch (BusinessException ignored) {
            return null;
        }
    }

    private FetchedPage fetch(HttpUrl initial) {
        HttpUrl current = checkedUrl(initial, "书源链接");
        for (int attempt = 0; attempt <= MAX_REDIRECTS; attempt++) {
            Request request = new Request.Builder()
                    .url(current)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Referer", sourceBaseUrl().toString())
                    .get()
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    if (location == null || attempt == MAX_REDIRECTS) {
                        throw new BusinessException(503, "书源重定向次数过多");
                    }
                    HttpUrl next = current.resolve(location);
                    if (next == null) throw new BusinessException(503, "书源重定向地址无效");
                    current = checkedUrl(next, "书源重定向地址");
                    continue;
                }
                if (!response.isSuccessful()) {
                    throw new BusinessException(503, "品书斋暂时无法访问（HTTP " + response.code() + "）");
                }
                ResponseBody body = response.body();
                String html = body == null ? "" : body.string();
                HttpUrl finalUrl = checkedUrl(response.request().url(), "书源响应地址");
                return new FetchedPage(html, finalUrl);
            } catch (BusinessException ex) {
                throw ex;
            } catch (IOException ex) {
                log.warn("Book source request failed url={} message={}", current, ex.getMessage());
                throw new BusinessException(503, "品书斋暂时无法访问，请稍后重试");
            }
        }
        throw new BusinessException(503, "书源重定向次数过多");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class FetchedPage {
        private final String body;
        private final HttpUrl url;

        private FetchedPage(String body, HttpUrl url) {
            this.body = body;
            this.url = url;
        }
    }
}
