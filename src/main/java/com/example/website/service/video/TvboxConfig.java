package com.example.website.service.video;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TvboxConfig {
    private final List<TvboxSite> sites;
    private final Map<String, TvboxSite> byKey;

    public TvboxConfig(List<TvboxSite> sites) {
        this.sites = Collections.unmodifiableList(sites);
        Map<String, TvboxSite> indexed = new LinkedHashMap<>();
        for (TvboxSite site : sites) indexed.put(site.getKey(), site);
        this.byKey = Collections.unmodifiableMap(indexed);
    }

    public List<TvboxSite> getSites() { return sites; }

    public TvboxSite site(String key) {
        return byKey.get(key);
    }
}
