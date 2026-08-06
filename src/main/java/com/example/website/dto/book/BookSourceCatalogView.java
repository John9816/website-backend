package com.example.website.dto.book;

import lombok.Getter;

/** A curated entry point for book and reading resources. */
@Getter
public class BookSourceCatalogView {
    private String id;
    private String name;
    private String kind;
    private String description;
    private String catalogUrl;
    private String importUrl;
    private boolean maintained;
    private String status;
    private boolean operational;

    public BookSourceCatalogView(String id, String name, String kind, String description,
                                 String catalogUrl, String importUrl, boolean maintained) {
        this(id, name, kind, description, catalogUrl, importUrl, maintained,
                maintained ? "ready" : "catalog-only", maintained);
    }

    public BookSourceCatalogView(String id, String name, String kind, String description,
                                 String catalogUrl, String importUrl, boolean maintained,
                                 String status, boolean operational) {
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.description = description;
        this.catalogUrl = catalogUrl;
        this.importUrl = importUrl;
        this.maintained = maintained;
        this.status = status;
        this.operational = operational;
    }
}
