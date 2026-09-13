-- 知识库：文档表
CREATE TABLE IF NOT EXISTS kb_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL COMMENT '所属空间ID',
    user_id BIGINT NOT NULL COMMENT '创建者ID',
    parent_id BIGINT COMMENT '父文档ID（用于层级结构）',
    title VARCHAR(500) NOT NULL COMMENT '文档标题',
    content_markdown LONGTEXT COMMENT 'Markdown格式内容',
    content_html LONGTEXT COMMENT 'HTML格式内容',
    summary TEXT COMMENT '文档摘要',
    tags_json VARCHAR(1000) COMMENT '标签JSON',
    icon VARCHAR(200) COMMENT '文档图标',
    cover_url VARCHAR(1000) COMMENT '封面图URL',
    is_public BOOLEAN DEFAULT FALSE COMMENT '是否公开',
    share_token VARCHAR(64) COMMENT '分享令牌',
    view_count INT DEFAULT 0 COMMENT '浏览次数',
    word_count INT DEFAULT 0 COMMENT '字数',
    sort_order INT DEFAULT 0 COMMENT '排序',
    is_deleted BOOLEAN DEFAULT FALSE COMMENT '是否删除（软删除）',
    deleted_at DATETIME COMMENT '删除时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_space_user (space_id, user_id),
    INDEX idx_user_updated (user_id, updated_at, id),
    INDEX idx_parent (parent_id, sort_order),
    INDEX idx_share_token (share_token),
    INDEX idx_public (is_public, updated_at),
    FULLTEXT idx_title_content (title, content_markdown) WITH PARSER ngram,

    FOREIGN KEY (space_id) REFERENCES kb_space(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档表';

-- 知识库：文档版本历史表
CREATE TABLE IF NOT EXISTS kb_document_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL COMMENT '文档ID',
    version_number INT NOT NULL COMMENT '版本号',
    title VARCHAR(500) NOT NULL COMMENT '标题',
    content_markdown LONGTEXT COMMENT 'Markdown内容',
    content_html LONGTEXT COMMENT 'HTML内容',
    change_summary VARCHAR(1000) COMMENT '变更说明',
    created_by BIGINT NOT NULL COMMENT '创建者ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_document_version (document_id, version_number DESC),

    FOREIGN KEY (document_id) REFERENCES kb_document(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档版本历史表';

-- 知识库：文档分享记录表
CREATE TABLE IF NOT EXISTS kb_document_share (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL COMMENT '文档ID',
    share_token VARCHAR(64) NOT NULL UNIQUE COMMENT '分享令牌',
    password VARCHAR(200) COMMENT '访问密码（加密）',
    expire_at DATETIME COMMENT '过期时间',
    max_views INT COMMENT '最大访问次数',
    view_count INT DEFAULT 0 COMMENT '已访问次数',
    is_active BOOLEAN DEFAULT TRUE COMMENT '是否有效',
    created_by BIGINT NOT NULL COMMENT '创建者ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_token (share_token, is_active),
    INDEX idx_document (document_id, is_active),
    INDEX idx_expire (expire_at, is_active),

    FOREIGN KEY (document_id) REFERENCES kb_document(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档分享表';

-- 知识库：文档收藏表
CREATE TABLE IF NOT EXISTS kb_document_favorite (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL COMMENT '文档ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_document_user (document_id, user_id),
    INDEX idx_user_created (user_id, created_at DESC),

    FOREIGN KEY (document_id) REFERENCES kb_document(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档收藏表';

-- 移除一个用户只能创建一个空间的限制
-- （原代码中的业务逻辑需要修改）

-- 添加空间配置字段
ALTER TABLE kb_space ADD COLUMN IF NOT EXISTS config_json TEXT COMMENT '空间配置JSON';
ALTER TABLE kb_space ADD COLUMN IF NOT EXISTS is_default BOOLEAN DEFAULT FALSE COMMENT '是否默认空间';
ALTER TABLE kb_space ADD COLUMN IF NOT EXISTS document_count INT DEFAULT 0 COMMENT '文档数量';

-- 更新空间索引
CREATE INDEX IF NOT EXISTS idx_user_default ON kb_space(user_id, is_default);
CREATE INDEX IF NOT EXISTS idx_user_sort ON kb_space(user_id, sort_order, id);
