CREATE TABLE IF NOT EXISTS music_share (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    user_id           BIGINT        NOT NULL,
    source            VARCHAR(20)   NOT NULL,
    song_id           VARCHAR(100)  NOT NULL,
    name              VARCHAR(300)  NOT NULL,
    artist            VARCHAR(300)  DEFAULT NULL,
    album             VARCHAR(300)  DEFAULT NULL,
    cover_url         VARCHAR(1000) DEFAULT NULL,
    duration_sec      INT           DEFAULT NULL,
    requested_quality VARCHAR(20)   NOT NULL,
    token             VARCHAR(64)   NOT NULL,
    expires_at        DATETIME      DEFAULT NULL,
    view_count        INT           NOT NULL DEFAULT 0,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_music_share_user_song (user_id, source, song_id),
    UNIQUE KEY uk_music_share_token (token),
    KEY idx_music_share_user_created (user_id, created_at, id),
    CONSTRAINT fk_music_share_user FOREIGN KEY (user_id)
        REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per-user public music share tokens';
