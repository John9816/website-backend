-- 学城编辑器（xuecheng-editor）迁入 website-backend 的存储表。
-- 编辑器后端原本运行在 Cloudflare Workers + D1 上，全部业务集合（docs/users/prefs/
-- accessRequests/notifications/templates/trash/interactions/visitors/presence/sessions/
-- loginFails）都以 JSON 快照形式存放在单张 kv(key,value) 表中。这里 1:1 复刻该模型，
-- 保证编辑器前端与业务逻辑零改动地移植到 Java + MariaDB。

USE website_java;

CREATE TABLE IF NOT EXISTS editor_kv (
    k       VARCHAR(128) NOT NULL,
    v       LONGTEXT     NOT NULL,
    PRIMARY KEY (k)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='xuecheng-editor KV snapshot store';

-- 说明：docs / templates 两个集合在 D1 侧被拆成 <name>#0..N-1 + <name>#meta 多行，
-- 这是为了绕开 D1 单值大小上限。MariaDB 的 LONGTEXT 可容纳 4GB，无需分片，
-- Java 侧 Store 会把分片行合并后以单个 <name> 键存储，并对前端保持数组语义不变。
