package com.example.website.service.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaVideoProviderExecutorTest {

    @Test
    void supportsRegisteredShortDramaScriptWithPureJavaAdapter() {
        TvboxSite site = site();
        assertTrue(JavaVideoProviderExecutor.supports(site));
    }

    @Test
    void mapsShortDramaHomeSearchDetailAndPlayWithoutAWorker() {
        ObjectMapper mapper = new ObjectMapper();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            String query = chain.request().url().query();
            if (query != null && query.contains("duanjuId=")) {
                return response(chain, "{\"result\":{\"title\":\"纯Java短剧\","
                        + "\"coverImageUrl\":\"https://img.example/a.jpg\",\"desc\":\"简介\","
                        + "\"episodeList\":[{\"index\":1,\"playUrl\":"
                        + "\"https://dj.youlishipin.com/duanju/video302?episodeId=ep-1\"}]}}");
            }
            return response(chain, "{\"result\":{\"elements\":[{\"contents\":[{\"duanjuVo\":{"
                    + "\"duanjuId\":\"b1\",\"source\":\"jiuzhou\",\"title\":\"纯Java短剧\","
                    + "\"coverImageUrl\":\"https://img.example/a.jpg\",\"total\":10}}]}]}}");
        }).build();
        JavaVideoProviderExecutor executor = new JavaVideoProviderExecutor(client, mapper);

        Map<String, Object> home = executor.execute(site(), "home", 1, 20,
                null, null, Collections.emptyMap(), null, null, null);
        assertEquals(1, ((java.util.List<?>) home.get("categories")).size());
        assertEquals(1, ((java.util.List<?>) home.get("list")).size());

        Map<String, Object> search = executor.execute(site(), "search", 1, 20,
                "纯Java", null, Collections.emptyMap(), null, null, null);
        assertEquals(1, ((java.util.List<?>) search.get("list")).size());

        Map<String, Object> detail = executor.execute(site(), "detail", 1, 20,
                null, null, Collections.emptyMap(), "西饭@b1#jiuzhou", null, null);
        assertEquals("纯Java短剧", detail.get("title"));
        assertEquals(1, ((java.util.List<?>) detail.get("episodes")).size());

        Map<String, Object> play = executor.execute(site(), "play", 1, 20,
                null, null, Collections.emptyMap(), "西饭@b1#jiuzhou",
                "https://dj.youlishipin.com/duanju/video302?episodeId=ep-1", "西饭");
        assertEquals("mp4", play.get("format"));
        assertEquals(Boolean.TRUE, play.get("playable"));
    }

    private TvboxSite site() {
        ObjectMapper mapper = new ObjectMapper();
        return new TvboxConfigParser(mapper, 64 * 1024).parse("{\"sites\":[{"
                + "\"key\":\"剧和短剧\",\"name\":\"聚合短剧\",\"type\":3,"
                + "\"api\":\"https://16409.kstore.vip/js/demo.js\"}]}").site("剧和短剧");
    }

    private Response response(okhttp3.Interceptor.Chain chain, String body) throws IOException {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }
}
