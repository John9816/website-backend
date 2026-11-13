-- 用户听歌时长按日聚合：支撑周榜 / 月榜 / 我的排名。
-- 心跳上报按 (user_id, stat_date) 原子累加，一行一天，避免高频流水撑大表。
CREATE TABLE IF NOT EXISTS music_listen_stat (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    total_sec BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_listen_stat_user_date (user_id, stat_date),
    KEY idx_listen_stat_date (stat_date, total_sec)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
