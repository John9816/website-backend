package com.example.website.module.video.application;

import com.example.website.module.video.api.dto.VideoSourceCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TvboxConfigParserTest {

    private final TvboxConfigParser parser = new TvboxConfigParser(new ObjectMapper(), 64 * 1024);

    @Test
    void parsesSitesAndPreservesProviderFieldsWithoutExecutingThem() {
        TvboxConfig config = parser.parse("{" +
                "\"sites\":[" +
                "{\"key\":\"cms\",\"name\":\"CMS\",\"type\":0,\"api\":\"https://cms.example/api.php/provide/vod/\",\"searchable\":1,\"quickSearch\":1,\"filterable\":1,\"headers\":{\"User-Agent\":\"Duck\"}}," +
                "{\"key\":\"js\",\"name\":\"剧和短剧\",\"type\":3,\"api\":\"https://provider.example/rule.js\",\"ext\":{\"token\":\"x\"}}," +
                "{\"key\":\"dex\",\"name\":\"Android\",\"type\":3,\"api\":\"csp_Test\",\"jar\":\"https://provider.example/spider.jar\"}" +
                "],\"parses\":[],\"lives\":[]}");

        assertEquals(3, config.getSites().size());
        TvboxSite cms = config.site("cms");
        assertEquals(VideoSourceCapability.CMS_HTTP, cms.getCapability());
        assertTrue(cms.isSearchable());
        assertTrue(cms.isQuickSearch());
        assertTrue(cms.isFilterable());
        assertEquals("Duck", cms.getHeaders().get("User-Agent"));

        TvboxSite js = config.site("js");
        assertEquals(VideoSourceCapability.TVBOX_JS, js.getCapability());
        assertEquals("x", ((Map<?, ?>) js.getExt()).get("token"));

        TvboxSite dex = config.site("dex");
        assertEquals(VideoSourceCapability.ANDROID_DEX, dex.getCapability());
        assertNotNull(dex.getJar());
        assertFalse(dex.isExecutable());
    }

    @Test
    void classifiesXbpqExtAndRegisteredPythonProvider() {
        TvboxConfig config = parser.parse("{\"sites\":[" +
                "{\"key\":\"xbpq\",\"name\":\"XBPQ\",\"type\":3,\"api\":\"csp_XBPQ\",\"ext\":\"https://provider.example/rule.json\"}," +
                "{\"key\":\"py\",\"name\":\"Python\",\"type\":4,\"api\":\"https://worker.example/rules/demo.py\"}]}");

        assertEquals(VideoSourceCapability.DRPY_JS, config.site("xbpq").getCapability());
        assertEquals(VideoSourceCapability.DRPY_PY, config.site("py").getCapability());
        assertTrue(config.site("py").isExecutable());
    }

    @Test
    void rejectsMalformedOrOversizedConfig() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("not-json"));
        String oversized = "{" + "\"sites\":[] , \"padding\":\"" + "x".repeat(64 * 1024) + "\"}";
        assertThrows(IllegalArgumentException.class, () -> parser.parse(oversized));
    }

    @Test
    void requiresSiteKeyAndRejectsDuplicateKeys() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"sites\":[{\"name\":\"missing\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                "{\"sites\":[{\"key\":\"same\",\"name\":\"one\"},{\"key\":\"same\",\"name\":\"two\"}]}"));
    }
}
