package com.example.website;

import com.example.website.module.reading.application.ImportedBookSourceReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 DSL 求值器能执行标准阅读书源规则（纯单元，不加载 Spring）。 */
class BookSourceDslTests {

    private final ObjectMapper mapper = new ObjectMapper();

    private ImportedBookSourceReader engine() throws Exception {
        Constructor<ImportedBookSourceReader> ctor = ImportedBookSourceReader.class.getDeclaredConstructor(
                com.example.website.module.reading.infrastructure.persistence.UserBookSourceRepository.class,
                ObjectMapper.class, OkHttpClient.class);
        ctor.setAccessible(true);
        return ctor.newInstance(null, mapper, new OkHttpClient());
    }

    @SuppressWarnings("unchecked")
    private List<Element> resolve(ImportedBookSourceReader e, Element root, String rule) throws Exception {
        Method m = ImportedBookSourceReader.class.getDeclaredMethod("resolve", Element.class, String.class);
        m.setAccessible(true);
        return (List<Element>) m.invoke(e, root, rule);
    }

    private String extract(ImportedBookSourceReader e, Element root, String rule) throws Exception {
        Method m = ImportedBookSourceReader.class.getDeclaredMethod("extract", Element.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(e, root, rule, "https://example.com");
    }

    private Document doc() {
        return Jsoup.parse("<html><body>"
                + "<div class='result-item'><h3 class='title'><a href='/book/1.html'>斗破苍穹</a></h3>"
                + "<span class='author'>天蚕土豆</span></div>"
                + "<div class='result-item'><h3 class='title'><a href='/book/2.html'>遮天</a></h3>"
                + "<span class='author'>辰东</span></div>"
                + "<div id='content'><p>第一段正文</p><p>第二段正文</p></div>"
                + "</body></html>", "https://example.com");
    }

    @Test
    void classTagChainAndIndex() throws Exception {
        ImportedBookSourceReader e = engine();
        Document d = doc();
        // 标准规则: 选中两个 .result-item
        List<Element> rows = resolve(e, d, "class.result-item");
        assertEquals(2, rows.size(), "class.x 应选中 2 行");
        // 链式: 每行中 .title a 的文本
        assertEquals("斗破苍穹", extract(e, rows.get(0), "class.title@tag.a@text"));
        // .N 索引: 第 0 行的书名
        assertEquals("斗破苍穹", extract(e, d, "class.result-item.0@class.title@tag.a@text"));
        // @href 属性
        assertEquals("/book/1.html", extract(e, rows.get(0), "class.title@tag.a@href"));
    }

    @Test
    void multiIndexAndTextNodes() throws Exception {
        ImportedBookSourceReader e = engine();
        Document d = doc();
        // !0:1 多索引
        List<Element> rows = resolve(e, d, "class.result-item!0:1");
        assertEquals(2, rows.size());
        // textNodes 正文（含换行）
        String content = extract(e, d, "id.content@textNodes");
        assertTrue(content.contains("第一段正文") && content.contains("第二段正文"));
    }

    @Test
    void regexReplaceAndFallback() throws Exception {
        ImportedBookSourceReader e = engine();
        Document d = doc();
        // ##正则##替换: 去掉"作者"前缀之类
        String author = extract(e, d, "class.author@text##天蚕##");
        assertEquals("土豆", author);
        // || 回退: 第一个选择器无结果时用第二个
        assertEquals("斗破苍穹", extract(e, d, "tag.nonexistent@text||class.title@tag.a@text"));
    }

    @Test
    void nativeCssStillWorks() throws Exception {
        ImportedBookSourceReader e = engine();
        Document d = doc();
        // 向后兼容：内置品书斋用的原生 CSS + @attr
        assertEquals("斗破苍穹", extract(e, d, ".title a@text"));
        assertEquals("2", String.valueOf(resolve(e, d, ".result-item").size()));
    }
}
