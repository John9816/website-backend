package com.example.website.module.video.application;

import com.example.website.config.AppProperties;
import com.example.website.config.AppProperties.VideoSources.WarehouseConfig;
import okhttp3.HttpUrl;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates and exposes the fixed, deployment-owned source warehouse list. */
@Service
public class VideoSourceCatalog {
    public static final int MAX_ID_LENGTH = 64;
    public static final int MAX_NAME_LENGTH = 128;
    public static final int MAX_SITE_KEY_LENGTH = 128;
    public static final int MAX_URL_LENGTH = 2048;
    private static final String SOURCE_SEPARATOR = "::";

    private final List<WarehouseConfig> enabledWarehouses;
    private final String defaultSourceId;

    public VideoSourceCatalog(AppProperties properties) {
        if (properties == null || properties.getVideoSources() == null) {
            throw new IllegalArgumentException("视频源配置不能为空");
        }
        List<WarehouseConfig> configured = properties.getVideoSources().getWarehouses();
        if (configured == null || configured.isEmpty()) {
            throw new IllegalArgumentException("至少需要配置一个影视仓库");
        }

        Set<String> ids = new HashSet<>();
        List<WarehouseConfig> enabled = new ArrayList<>();
        for (WarehouseConfig warehouse : configured) {
            validateWarehouse(warehouse, ids);
            if (warehouse.isEnabled()) {
                enabled.add(copyOf(warehouse));
            }
        }
        enabled.sort(Comparator.comparingInt(WarehouseConfig::getSortOrder)
                .thenComparing(WarehouseConfig::getId));
        if (enabled.isEmpty()) {
            throw new IllegalArgumentException("至少需要启用一个影视仓库");
        }

        String configuredDefault = properties.getVideoSources().getDefaultSourceId();
        String candidateDefault = configuredDefault == null ? "" : configuredDefault.trim();
        if (candidateDefault.isEmpty()) {
            WarehouseConfig first = enabled.get(0);
            candidateDefault = composeSourceId(first.getId(), first.getDefaultSiteKey());
        }
        SourceRef defaultRef = parseSourceId(candidateDefault);
        WarehouseConfig defaultWarehouse = find(enabled, defaultRef.getWarehouseId());
        if (defaultWarehouse == null
                || !defaultWarehouse.getDefaultSiteKey().equals(defaultRef.getSiteKey())) {
            throw new IllegalArgumentException("默认影视源必须属于启用仓库的 defaultSiteKey: " + candidateDefault);
        }
        this.enabledWarehouses = Collections.unmodifiableList(enabled);
        this.defaultSourceId = candidateDefault;
    }

    public List<WarehouseConfig> enabledWarehouses() {
        List<WarehouseConfig> copies = new ArrayList<>();
        for (WarehouseConfig warehouse : enabledWarehouses) {
            copies.add(copyOf(warehouse));
        }
        return Collections.unmodifiableList(copies);
    }

    public WarehouseConfig requireWarehouse(String warehouseId) {
        if (warehouseId == null || warehouseId.trim().isEmpty()) {
            throw new IllegalArgumentException("仓库 id 不能为空");
        }
        WarehouseConfig warehouse = find(enabledWarehouses, warehouseId);
        if (warehouse == null) {
            throw new IllegalArgumentException("影视仓库未启用或不存在: " + warehouseId);
        }
        return copyOf(warehouse);
    }

    public String defaultSourceId() {
        return defaultSourceId;
    }

    public String composeSourceId(String warehouseId, String siteKey) {
        validatePart(warehouseId, "仓库 id", MAX_ID_LENGTH, true);
        validatePart(siteKey, "站点 key", MAX_SITE_KEY_LENGTH, false);
        return warehouseId + SOURCE_SEPARATOR + siteKey;
    }

    public SourceRef parseSourceId(String sourceId) {
        if (sourceId == null || sourceId.length() > MAX_ID_LENGTH + SOURCE_SEPARATOR.length()
                + MAX_SITE_KEY_LENGTH) {
            throw new IllegalArgumentException("影视源 id 长度非法");
        }
        int separator = sourceId.indexOf(SOURCE_SEPARATOR);
        if (separator <= 0 || separator != sourceId.lastIndexOf(SOURCE_SEPARATOR)) {
            throw new IllegalArgumentException("影视源 id 必须使用 warehouseId::siteKey 格式");
        }
        String warehouseId = sourceId.substring(0, separator);
        String siteKey = sourceId.substring(separator + SOURCE_SEPARATOR.length());
        validatePart(warehouseId, "仓库 id", MAX_ID_LENGTH, true);
        validatePart(siteKey, "站点 key", MAX_SITE_KEY_LENGTH, false);
        return new SourceRef(warehouseId, siteKey);
    }

    private void validateWarehouse(WarehouseConfig warehouse, Set<String> ids) {
        if (warehouse == null) {
            throw new IllegalArgumentException("影视仓库配置不能为空");
        }
        validatePart(warehouse.getId(), "仓库 id", MAX_ID_LENGTH, true);
        if (!ids.add(warehouse.getId())) {
            throw new IllegalArgumentException("影视仓库 id 重复: " + warehouse.getId());
        }
        validatePart(warehouse.getName(), "仓库名称", MAX_NAME_LENGTH, false);
        validatePart(warehouse.getDefaultSiteKey(), "默认站点 key", MAX_SITE_KEY_LENGTH, false);
        if (warehouse.getKind() == null) {
            throw new IllegalArgumentException("仓库格式不能为空: " + warehouse.getId());
        }
        validateUrl(warehouse.getConfigUrl(), warehouse.isAllowHttp());
    }

    private void validateUrl(String raw, boolean allowHttp) {
        if (raw == null || raw.trim().isEmpty() || raw.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("影视仓库 URL 长度或内容非法");
        }
        HttpUrl url = HttpUrl.parse(raw.trim());
        if (url == null || !("http".equals(url.scheme()) || "https".equals(url.scheme()))) {
            throw new IllegalArgumentException("影视仓库 URL 必须是 HTTP(S)");
        }
        if ("http".equals(url.scheme()) && !allowHttp) {
            throw new IllegalArgumentException("影视仓库默认只允许 HTTPS");
        }
        if (!url.username().isEmpty() || !url.password().isEmpty()) {
            throw new IllegalArgumentException("影视仓库 URL 不得包含用户凭据");
        }
        String host = url.host();
        if (host.equalsIgnoreCase("localhost") || host.toLowerCase().endsWith(".localhost")
                || isPrivateIpLiteral(host)) {
            throw new IllegalArgumentException("影视仓库 URL 不得指向本机或内网地址");
        }
    }

    private boolean isPrivateIpLiteral(String host) {
        if (host.indexOf(':') < 0 && !host.matches("[0-9.]+")) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                return true;
            }
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) {
                int first = bytes[0] & 0xff;
                int second = bytes[1] & 0xff;
                return first == 100 && second >= 64 && second <= 127;
            }
            return (bytes[0] & 0xfe) == 0xfc;
        } catch (UnknownHostException ignored) {
            return true;
        }
    }

    private void validatePart(String value, String label, int maxLength, boolean warehouseId) {
        if (value == null || value.isEmpty() || value.length() > maxLength
                || value.trim().isEmpty() || containsControl(value)
                || value.contains(SOURCE_SEPARATOR)) {
            throw new IllegalArgumentException(label + "长度或格式非法");
        }
    }

    private boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private WarehouseConfig find(List<WarehouseConfig> warehouses, String id) {
        for (WarehouseConfig warehouse : warehouses) {
            if (warehouse.getId().equals(id)) {
                return warehouse;
            }
        }
        return null;
    }

    private WarehouseConfig copyOf(WarehouseConfig source) {
        WarehouseConfig copy = new WarehouseConfig();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setConfigUrl(source.getConfigUrl());
        copy.setKind(source.getKind());
        copy.setEnabled(source.isEnabled());
        copy.setAllowHttp(source.isAllowHttp());
        copy.setSortOrder(source.getSortOrder());
        copy.setDefaultSiteKey(source.getDefaultSiteKey());
        return copy;
    }

    public static final class SourceRef {
        private final String warehouseId;
        private final String siteKey;

        private SourceRef(String warehouseId, String siteKey) {
            this.warehouseId = warehouseId;
            this.siteKey = siteKey;
        }

        public String getWarehouseId() {
            return warehouseId;
        }

        public String getSiteKey() {
            return siteKey;
        }
    }
}
