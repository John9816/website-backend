USE website;

-- Knowledge base spaces (top-level container per user, similar to Meituan Xuecheng spaces)
CREATE TABLE IF NOT EXISTS kb_space (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500) DEFAULT NULL,
    icon        VARCHAR(255) DEFAULT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    doc_count   INT          NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_kb_space_user_sort (user_id, sort_order, id),
    CONSTRAINT fk_kb_space_user FOREIGN KEY (user_id)
        REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Knowledge base spaces';

-- Knowledge base documents (Tiptap-edited rich text, hierarchical via parent_id)
CREATE TABLE IF NOT EXISTS kb_doc (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    space_id      BIGINT       NOT NULL,
    parent_id     BIGINT       DEFAULT NULL,
    user_id       BIGINT       NOT NULL,
    title         VARCHAR(200) NOT NULL,
    summary       VARCHAR(500) DEFAULT NULL,
    content_json  LONGTEXT     DEFAULT NULL,
    content_html  LONGTEXT     DEFAULT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'draft',
    sort_order    INT          NOT NULL DEFAULT 0,
    version_no    INT          NOT NULL DEFAULT 1,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_kb_doc_space_parent (space_id, parent_id, sort_order, id),
    KEY idx_kb_doc_user_updated (user_id, updated_at, id),
    KEY idx_kb_doc_title (title),
    CONSTRAINT fk_kb_doc_space FOREIGN KEY (space_id)
        REFERENCES kb_space (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Knowledge base documents';

-- User-scoped tags
CREATE TABLE IF NOT EXISTS kb_tag (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    name        VARCHAR(50)  NOT NULL,
    color       VARCHAR(20)  DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_tag_user_name (user_id, name),
    CONSTRAINT fk_kb_tag_user FOREIGN KEY (user_id)
        REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Knowledge base tags';

-- Doc-Tag many-to-many
CREATE TABLE IF NOT EXISTS kb_doc_tag (
    doc_id  BIGINT NOT NULL,
    tag_id  BIGINT NOT NULL,
    PRIMARY KEY (doc_id, tag_id),
    KEY idx_kb_doc_tag_tag (tag_id, doc_id),
    CONSTRAINT fk_kb_doc_tag_doc FOREIGN KEY (doc_id)
        REFERENCES kb_doc (id) ON DELETE CASCADE,
    CONSTRAINT fk_kb_doc_tag_tag FOREIGN KEY (tag_id)
        REFERENCES kb_tag (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Doc-Tag many-to-many';

-- Doc version snapshots (one row per save)
CREATE TABLE IF NOT EXISTS kb_doc_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id          BIGINT       NOT NULL,
    version_no      INT          NOT NULL,
    title           VARCHAR(200) NOT NULL,
    summary         VARCHAR(500) DEFAULT NULL,
    content_json    LONGTEXT     DEFAULT NULL,
    content_html    LONGTEXT     DEFAULT NULL,
    editor_user_id  BIGINT       NOT NULL,
    change_note     VARCHAR(500) DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_doc_version (doc_id, version_no),
    KEY idx_kb_doc_version_doc (doc_id, id),
    CONSTRAINT fk_kb_doc_version_doc FOREIGN KEY (doc_id)
        REFERENCES kb_doc (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Knowledge base doc version history';

-- Public share tokens (one per doc)
CREATE TABLE IF NOT EXISTS kb_doc_share (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id      BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    token       VARCHAR(64)  NOT NULL,
    enabled     TINYINT(1)   NOT NULL DEFAULT 1,
    expires_at  DATETIME     DEFAULT NULL,
    view_count  INT          NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_doc_share_doc (doc_id),
    UNIQUE KEY uk_kb_doc_share_token (token),
    CONSTRAINT fk_kb_doc_share_doc FOREIGN KEY (doc_id)
        REFERENCES kb_doc (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Knowledge base doc share tokens';
