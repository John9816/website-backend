package com.example.website.dto.video;

/** Immutable public representation of one flattened source site. */
public final class VideoSourceView {
    private final String id;
    private final String name;
    private final String warehouseId;
    private final String warehouseName;
    private final String siteKey;
    private final VideoSourceKind kind;
    private final VideoSourceCapability capability;
    private final boolean enabled;
    private final boolean defaultSource;
    private final String reason;
    private final int sortOrder;

    public VideoSourceView(String id,
                           String name,
                           String warehouseId,
                           String warehouseName,
                           String siteKey,
                           VideoSourceKind kind,
                           VideoSourceCapability capability,
                           boolean enabled,
                           boolean defaultSource,
                           String reason,
                           int sortOrder) {
        this.id = id;
        this.name = name;
        this.warehouseId = warehouseId;
        this.warehouseName = warehouseName;
        this.siteKey = siteKey;
        this.kind = kind;
        this.capability = capability;
        this.enabled = enabled;
        this.defaultSource = defaultSource;
        this.reason = reason;
        this.sortOrder = sortOrder;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getWarehouseId() {
        return warehouseId;
    }

    public String getWarehouseName() {
        return warehouseName;
    }

    public String getSiteKey() {
        return siteKey;
    }

    public VideoSourceKind getKind() {
        return kind;
    }

    public VideoSourceCapability getCapability() {
        return capability;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isDefaultSource() {
        return defaultSource;
    }

    public String getReason() {
        return reason;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
