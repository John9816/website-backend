-- 学城文档（editor）后端存储表
-- 设计：每用户一行，各功能集合以 JSON 数组存于 LONGTEXT，1:1 对应原 Node 文件存储结构，
--       便于从 /opt/website/shared/xuecheng/users/{id}/*.json 原样迁移。

CREATE TABLE IF NOT EXISTS editor_user_data (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    docs LONGTEXT,
    templates LONGTEXT,
    trash LONGTEXT,
    notifications LONGTEXT,
    comments LONGTEXT,
    mentions LONGTEXT,
    access_requests LONGTEXT,
    created_at DATETIME NULL,
    updated_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_editor_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 分享 token -> 文档 owner 的全局映射（对应原 shares.json）
CREATE TABLE IF NOT EXISTS editor_share (
    token VARCHAR(96) NOT NULL,
    user_id BIGINT NOT NULL,
    doc_id VARCHAR(64) NULL,
    created_at DATETIME NULL,
    PRIMARY KEY (token),
    KEY idx_editor_share_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 在线心跳（对应原 presence.json，全局，按 at 时间过滤 TTL）
CREATE TABLE IF NOT EXISTS editor_presence (
    user_id BIGINT NOT NULL,
    name VARCHAR(128) NULL,
    doc_id VARCHAR(64) NULL,
    at BIGINT NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
