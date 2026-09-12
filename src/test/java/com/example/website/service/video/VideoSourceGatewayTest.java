package com.example.website.service.video;

import com.example.website.config.AppProperties;
import com.example.website.config.AppProperties.VideoSources.WarehouseConfig;
import com.example.website.dto.video.VideoSourceCapability;
import com.example.website.dto.video.VideoSourceKind;
import com.example.website.dto.video.VideoSourceView;
import com.example.website.service.VideoSourceCatalog;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoSourceGatewayTest {

    @Test
    void forwardsOnlyRegisteredSourceToInProcessJavaExecutor() {
        VideoSourceCatalog catalog = catalog("ngzmods", "site");
        JavaVideoProviderExecutor executor = mock(JavaVideoProviderExecutor.class);
        when(executor.execute(isNull(), anyString(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.<String, Object>singletonMap("ok", Boolean.TRUE));
        VideoSourceGateway gateway = new VideoSourceGateway(catalog, executor,
                Collections.singletonList(source("ngzmods::site", "site", VideoSourceCapability.TVBOX_JS, true)));

        Map<String, Object> result = gateway.execute(
                "ngzmods::site", "search", 1, 20, "短剧", null,
                Collections.emptyMap(), null, null, null);

        assertEquals(Boolean.TRUE, result.get("ok"));
        verify(executor).execute(isNull(), anyString(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsUnknownOrDisabledSourcesBeforeJavaExecution() {
        VideoSourceCatalog catalog = catalog("ngzmods", "site");
        JavaVideoProviderExecutor executor = mock(JavaVideoProviderExecutor.class);
        VideoSourceGateway gateway = new VideoSourceGateway(catalog, executor, Arrays.asList(
                source("ngzmods::site", "site", VideoSourceCapability.TVBOX_JS, true),
                source("ngzmods::disabled", "disabled", VideoSourceCapability.TVBOX_JS, false)));

        assertThrows(IllegalArgumentException.class, () -> gateway.execute(
                "ngzmods::other", "home", 1, 20, null, null, Collections.emptyMap(), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> gateway.execute(
                "ngzmods::disabled", "home", 1, 20, null, null, Collections.emptyMap(), null, null, null));
        verify(executor, never()).execute(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsSourcesWithoutPureJavaImplementation() {
        VideoSourceCatalog catalog = catalog("ngzmods", "android");
        JavaVideoProviderExecutor executor = mock(JavaVideoProviderExecutor.class);
        VideoSourceGateway gateway = new VideoSourceGateway(catalog, executor,
                Collections.singletonList(source("ngzmods::android", "android",
                        VideoSourceCapability.UNSUPPORTED, true)));

        assertThrows(VideoProviderException.class, () -> gateway.execute(
                "ngzmods::android", "home", 1, 20, null, null, Collections.emptyMap(), null, null, null));
    }

    private VideoSourceCatalog catalog(String warehouseId, String defaultSiteKey) {
        AppProperties properties = new AppProperties();
        WarehouseConfig warehouse = new WarehouseConfig();
        warehouse.setId(warehouseId);
        warehouse.setName("仓库");
        warehouse.setConfigUrl("https://example.com/config.json");
        warehouse.setKind(VideoSourceKind.TVBOX_JSON);
        warehouse.setEnabled(true);
        warehouse.setAllowHttp(false);
        warehouse.setSortOrder(1);
        warehouse.setDefaultSiteKey(defaultSiteKey);
        properties.getVideoSources().setWarehouses(Collections.singletonList(warehouse));
        properties.getVideoSources().setDefaultSourceId(warehouseId + "::" + defaultSiteKey);
        return new VideoSourceCatalog(properties);
    }

    private VideoSourceView source(String id, String siteKey,
                                   VideoSourceCapability capability, boolean enabled) {
        return new VideoSourceView(id, siteKey, "ngzmods", "仓库", siteKey,
                VideoSourceKind.TVBOX_JSON, capability, enabled, false,
                capability == VideoSourceCapability.UNSUPPORTED ? "没有纯 Java Provider 实现" : null, 1);
    }
}
