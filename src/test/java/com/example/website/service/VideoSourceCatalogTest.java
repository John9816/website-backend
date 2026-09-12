package com.example.website.service;

import com.example.website.config.AppProperties;
import com.example.website.config.AppProperties.VideoSources.WarehouseConfig;
import com.example.website.dto.video.VideoSourceCapability;
import com.example.website.dto.video.VideoSourceKind;
import com.example.website.dto.video.VideoSourceView;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VideoSourceCatalogTest {

    @Test
    void exposesOnlyEnabledWarehousesInConfiguredOrderAndPreservesTheDefault() {
        AppProperties properties = properties(
                warehouse("newwex", "新无邪", "https://9280.kstore.vip/newwex.json",
                        VideoSourceKind.TVBOX_JSON, true, false, 20, "NewDouBan"),
                warehouse("disabled", "已停用", "https://example.com/disabled.json",
                        VideoSourceKind.TVBOX_JSON, false, false, 1, "site"),
                warehouse("ngzmods", "NGZMods", "https://16409.kstore.vip/tv/ngzmods.json",
                        VideoSourceKind.TVBOX_JSON, true, false, 10, "剧和短剧")
        );
        properties.getVideoSources().setDefaultSourceId("ngzmods::剧和短剧");

        VideoSourceCatalog catalog = new VideoSourceCatalog(properties);

        List<WarehouseConfig> enabled = catalog.enabledWarehouses();
        assertEquals(Arrays.asList("ngzmods", "newwex"),
                Arrays.asList(enabled.get(0).getId(), enabled.get(1).getId()));
        assertEquals("ngzmods::剧和短剧", catalog.defaultSourceId());
        assertEquals("https://9280.kstore.vip/newwex.json",
                catalog.requireWarehouse("newwex").getConfigUrl());
        assertThrows(IllegalArgumentException.class, () -> catalog.requireWarehouse("disabled"));
    }

    @Test
    void representsAndroidOnlySitesWithoutPretendingTheyAreExecutable() {
        VideoSourceView view = new VideoSourceView(
                "newwex::NewDouBan",
                "新无邪 / NewDouBan",
                "newwex",
                "新无邪",
                "NewDouBan",
                VideoSourceKind.TVBOX_JSON,
                VideoSourceCapability.ANDROID_DEX,
                true,
                false,
                "该站点依赖 Android classes.dex Provider",
                20
        );

        assertEquals(VideoSourceCapability.ANDROID_DEX, view.getCapability());
        assertEquals("该站点依赖 Android classes.dex Provider", view.getReason());
        assertFalse(view.isDefaultSource());
    }

    @Test
    void rejectsHttpUnlessTheWarehouseExplicitlyAllowsIt() {
        AppProperties rejected = properties(warehouse(
                "plain", "HTTP 仓库", "http://public.example/config.json",
                VideoSourceKind.TVBOX_JSON, true, false, 10, "site"));
        rejected.getVideoSources().setDefaultSourceId("plain::site");

        assertThrows(IllegalArgumentException.class, () -> new VideoSourceCatalog(rejected));

        AppProperties allowed = properties(warehouse(
                "plain", "HTTP 仓库", "http://public.example/config.json",
                VideoSourceKind.TVBOX_JSON, true, true, 10, "site"));
        allowed.getVideoSources().setDefaultSourceId("plain::site");

        assertEquals("plain", new VideoSourceCatalog(allowed).enabledWarehouses().get(0).getId());
    }

    @Test
    void rejectsCredentialsLocalhostLoopbackPrivateAndLinkLocalUrls() {
        List<String> invalidUrls = Arrays.asList(
                "https://user:secret@example.com/config.json",
                "https://localhost/config.json",
                "https://video.localhost/config.json",
                "https://127.0.0.1/config.json",
                "https://10.20.30.40/config.json",
                "https://172.16.0.1/config.json",
                "https://192.168.1.1/config.json",
                "https://169.254.10.20/config.json",
                "https://[::1]/config.json",
                "https://[fc00::1]/config.json",
                "https://[fe80::1]/config.json",
                "ftp://example.com/config.json"
        );

        for (String url : invalidUrls) {
            AppProperties properties = properties(warehouse(
                    "unsafe", "不安全仓库", url,
                    VideoSourceKind.TVBOX_JSON, true, false, 10, "site"));
            properties.getVideoSources().setDefaultSourceId("unsafe::site");
            assertThrows(IllegalArgumentException.class,
                    () -> new VideoSourceCatalog(properties), url);
        }
    }

    @Test
    void rejectsOversizedAndDuplicateConfigurationValues() {
        assertInvalid(warehouse(repeat('i', 65), "仓库", "https://example.com/config.json",
                VideoSourceKind.TVBOX_JSON, true, false, 10, "site"));
        assertInvalid(warehouse("valid", repeat('n', 129), "https://example.com/config.json",
                VideoSourceKind.TVBOX_JSON, true, false, 10, "site"));
        assertInvalid(warehouse("valid", "仓库", "https://example.com/" + repeat('u', 2030),
                VideoSourceKind.TVBOX_JSON, true, false, 10, "site"));

        AppProperties duplicates = properties(
                warehouse("same", "仓库一", "https://one.example/config.json",
                        VideoSourceKind.TVBOX_JSON, true, false, 10, "one"),
                warehouse("same", "仓库二", "https://two.example/config.json",
                        VideoSourceKind.TVBOX_JSON, true, false, 20, "two")
        );
        duplicates.getVideoSources().setDefaultSourceId("same::one");
        assertThrows(IllegalArgumentException.class, () -> new VideoSourceCatalog(duplicates));
    }

    @Test
    void composesAndParsesStableSourceIdsWithoutLosingUnicodeSiteKeys() {
        AppProperties properties = properties(warehouse(
                "ngzmods", "NGZMods", "https://16409.kstore.vip/tv/ngzmods.json",
                VideoSourceKind.TVBOX_JSON, true, false, 10, "剧和短剧"));
        properties.getVideoSources().setDefaultSourceId("ngzmods::剧和短剧");
        VideoSourceCatalog catalog = new VideoSourceCatalog(properties);

        String sourceId = catalog.composeSourceId("ngzmods", "剧和短剧");
        VideoSourceCatalog.SourceRef ref = catalog.parseSourceId(sourceId);

        assertEquals("ngzmods::剧和短剧", sourceId);
        assertEquals("ngzmods", ref.getWarehouseId());
        assertEquals("剧和短剧", ref.getSiteKey());
        assertEquals(sourceId, catalog.composeSourceId(ref.getWarehouseId(), ref.getSiteKey()));
        assertThrows(IllegalArgumentException.class, () -> catalog.parseSourceId("missing-delimiter"));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.composeSourceId("ngzmods::other", "site"));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.composeSourceId("ngzmods", "site::other"));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.composeSourceId("ngzmods", repeat('s', 129)));
    }

    @Test
    void rejectsADefaultThatDoesNotBelongToAnEnabledConfiguredWarehouse() {
        AppProperties properties = properties(warehouse(
                "disabled", "已停用", "https://example.com/config.json",
                VideoSourceKind.TVBOX_JSON, false, false, 10, "site"));
        properties.getVideoSources().setDefaultSourceId("disabled::site");

        assertThrows(IllegalArgumentException.class, () -> new VideoSourceCatalog(properties));
    }

    private void assertInvalid(WarehouseConfig warehouse) {
        AppProperties properties = properties(warehouse);
        properties.getVideoSources().setDefaultSourceId("valid::site");
        assertThrows(IllegalArgumentException.class, () -> new VideoSourceCatalog(properties));
    }

    private AppProperties properties(WarehouseConfig... warehouses) {
        AppProperties properties = new AppProperties();
        properties.getVideoSources().setWarehouses(Arrays.asList(warehouses));
        return properties;
    }

    private WarehouseConfig warehouse(String id,
                                      String name,
                                      String configUrl,
                                      VideoSourceKind kind,
                                      boolean enabled,
                                      boolean allowHttp,
                                      int sortOrder,
                                      String defaultSiteKey) {
        WarehouseConfig warehouse = new WarehouseConfig();
        warehouse.setId(id);
        warehouse.setName(name);
        warehouse.setConfigUrl(configUrl);
        warehouse.setKind(kind);
        warehouse.setEnabled(enabled);
        warehouse.setAllowHttp(allowHttp);
        warehouse.setSortOrder(sortOrder);
        warehouse.setDefaultSiteKey(defaultSiteKey);
        return warehouse;
    }

    private String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
