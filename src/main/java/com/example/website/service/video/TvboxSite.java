package com.example.website.service.video;

import com.example.website.dto.video.VideoSourceCapability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One TVBox site entry with metadata only; ext/api are never evaluated here. */
public final class TvboxSite {
    private final String key;
    private final String name;
    private final int type;
    private final String api;
    private final Object ext;
    private final String jar;
    private final String spider;
    private final Map<String, String> headers;
    private final boolean searchable;
    private final boolean quickSearch;
    private final boolean filterable;
    private final VideoSourceCapability capability;
    private final boolean executable;
    private final String reason;

    public TvboxSite(String key,
                     String name,
                     int type,
                     String api,
                     Object ext,
                     String jar,
                     String spider,
                     Map<String, String> headers,
                     boolean searchable,
                     boolean quickSearch,
                     boolean filterable,
                     VideoSourceCapability capability,
                     boolean executable,
                     String reason) {
        this.key = key;
        this.name = name;
        this.type = type;
        this.api = api;
        this.ext = ext;
        this.jar = jar;
        this.spider = spider;
        this.headers = headers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.searchable = searchable;
        this.quickSearch = quickSearch;
        this.filterable = filterable;
        this.capability = capability;
        this.executable = executable;
        this.reason = reason;
    }

    public String getKey() { return key; }
    public String getName() { return name; }
    public int getType() { return type; }
    public String getApi() { return api; }
    public Object getExt() { return ext; }
    public String getJar() { return jar; }
    public String getSpider() { return spider; }
    public Map<String, String> getHeaders() { return headers; }
    public boolean isSearchable() { return searchable; }
    public boolean isQuickSearch() { return quickSearch; }
    public boolean isFilterable() { return filterable; }
    public VideoSourceCapability getCapability() { return capability; }
    public boolean isExecutable() { return executable; }
    public String getReason() { return reason; }
}
