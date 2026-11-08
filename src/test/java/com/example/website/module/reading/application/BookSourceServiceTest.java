package com.example.website.module.reading.application;

import com.example.website.module.reading.api.dto.BookChapterListView;
import com.example.website.module.reading.api.dto.BookContentView;
import com.example.website.module.reading.api.dto.BookSearchPageView;
import com.example.website.module.reading.api.dto.BookView;
import okhttp3.OkHttpClient;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookSourceServiceTest {

    @Test
    void exposesCuratedSourceStatus() {
        BookSourceService service = new BookSourceService(new OkHttpClient());

        assertEquals(3, service.listCatalogs().size());
        assertEquals("7630", service.listCatalogs().get(0).getId());
        assertEquals("ready", service.listCatalogs().get(0).getStatus());
        assertTrue(service.listCatalogs().get(0).isOperational());
        assertFalse(service.listCatalogs().get(1).isOperational());
    }

    @Test
    void parsesSearchDetailChaptersAndCleanContent() {
        BookSourceService service = new BookSourceService(new OkHttpClient());
        String searchHtml = "<div class='bookuplist'><h3><span>共搜到<span> 1 </span>本</span></h3>"
                + "<div class='catdanlist'><ul><li>"
                + "<div><a><img src='/cover/活着.jpg'></a></div>"
                + "<div class='bookinfo'><h4><a href='/book/91.html'><span>活着</span></a></h4>"
                + "<p class='author'><a>余华</a></p><p class='cats'><a>文学</a></p>"
                + "<p>简介正文</p></div></li></ul></div></div>";
        BookSearchPageView search = service.parseSearchHtml(
                searchHtml, "https://www.pinshuzhai.com/page/1?s=活着", 1, 20);
        assertEquals(1, search.getList().size());
        BookView book = search.getList().get(0);
        assertEquals("活着", book.getName());
        assertEquals("余华", book.getAuthor());
        assertTrue(book.getCoverUrl().contains("%E6%B4%BB%E7%9D%80"));

        String detailHtml = "<head>"
                + "<meta property='og:novel:book_name' content='活着'>"
                + "<meta property='og:novel:author' content='余华'>"
                + "<meta property='og:image' content='/cover/活着.jpg'>"
                + "<meta property='og:novel:latest_chapter_name' content='第十章'>"
                + "<meta property='og:novel:status' content='完本'></head>"
                + "<div class='docspt'><div>作品简介：</div><p>一部长篇小说</p></div>"
                + "<div class='catb'><a>文学</a></div>"
                + "<div class='newzxzj'><div class='zjlist'>"
                + "<a href='/chapter/91-1.html'>前言</a>"
                + "<a href='/chapter/91-2.html'>第一章</a></div></div>";
        BookView detail = service.parseDetailHtml(
                detailHtml, "https://www.pinshuzhai.com/book/91.html",
                "https://www.pinshuzhai.com/book/91.html");
        assertEquals("余华", detail.getAuthor());
        assertEquals("一部长篇小说", detail.getIntro());
        assertEquals("第十章", detail.getLatestChapter());

        BookChapterListView chapters = service.parseChaptersHtml(
                detailHtml, "https://www.pinshuzhai.com/book/91.html");
        assertEquals(2, chapters.getList().size());
        assertEquals("第一章", chapters.getList().get(1).getTitle());

        String contentHtml = "<div class='readbox' id='chapter'><h2>前言</h2>"
                + "<div class='rdinfo'>活着 余华</div><div class='tjlm'>推荐阅读广告</div>"
                + "<div class='ydgong'>章节列表 下一章</div>"
                + "<div class='readcon'><div class='hid'>pinshuzhai.com</div>"
                + "<p>一位真正的作家永远只为内心写作。</p>"
                + "<p>第二段正文。<br>下一行正文。</p></div></div>";
        BookContentView content = service.parseContentHtml(
                contentHtml, "https://www.pinshuzhai.com/chapter/91-1.html",
                "https://www.pinshuzhai.com/chapter/91-1.html");
        assertTrue(content.getContent().contains("一位真正的作家"));
        assertTrue(content.getContent().contains("下一行正文"));
        assertFalse(content.getContent().contains("推荐阅读"));
        assertFalse(content.getContent().contains("品书斋"));
        assertFalse(content.getContent().startsWith("前言"));
    }

    @Test
    void rejectsUnknownSourcesAndOffDomainUrls() {
        BookSourceService service = new BookSourceService(new OkHttpClient());

        assertThrows(RuntimeException.class,
                () -> service.search("unknown", "活着", 1, 20));
        assertThrows(RuntimeException.class,
                () -> service.detail("7630", "https://example.com/book/1.html"));
    }

    @Test
    void parsesRecommendationAndRankingCards() {
        BookSourceService service = new BookSourceService(new OkHttpClient());
        String html = "<section class='list_recommend'><div class='el-col'>"
                + "<a class='cat-a'><div class='custom-hover image' style=\"background-image:url('/cover/a.jpg')\"></div></a>"
                + "<h4><a href='/book/1.html'>推荐书</a></h4><p class='font-12 mt-5'>作者甲</p></div></section>"
                + "<div class='banglist'><ul><li><a href='/bookcat/x'>「文学」</a>"
                + "<a class='bname' href='/book/2.html'>榜单书</a><span class='author'><a>作者乙</a></span></li></ul></div>";
        org.jsoup.nodes.Document doc = Jsoup.parse(html, "https://www.pinshuzhai.com/");

        assertEquals("推荐书", service.parseRecommendationCards(
                doc, "https://www.pinshuzhai.com/").get(0).getName());
        assertEquals("作者乙", service.parseRanking(
                doc, "https://www.pinshuzhai.com/").get(0).getAuthor());
    }
}
