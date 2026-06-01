CREATE TABLE IF NOT EXISTS user_playlist (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    user_id       BIGINT        NOT NULL,
    name          VARCHAR(300)  NOT NULL,
    cover_url     VARCHAR(1000) DEFAULT NULL,
    description   VARCHAR(2000) DEFAULT NULL,
    source        VARCHAR(20)   NOT NULL,
    source_id     VARCHAR(100)  NOT NULL,
    source_url    VARCHAR(1000) DEFAULT NULL,
    creator_name  VARCHAR(300)  DEFAULT NULL,
    track_count   INT           NOT NULL DEFAULT 0,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_playlist_user_source (user_id, source, source_id),
    KEY idx_user_playlist_user_created (user_id, created_at, id),
    CONSTRAINT fk_user_playlist_user FOREIGN KEY (user_id)
        REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per-user imported playlists';

CREATE TABLE IF NOT EXISTS user_playlist_item (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    playlist_id   BIGINT        NOT NULL,
    source        VARCHAR(20)   NOT NULL,
    song_id       VARCHAR(100)  NOT NULL,
    name          VARCHAR(300)  NOT NULL,
    artist        VARCHAR(300)  DEFAULT NULL,
    album         VARCHAR(300)  DEFAULT NULL,
    cover_url     VARCHAR(1000) DEFAULT NULL,
    duration_sec  INT           DEFAULT NULL,
    sort_order    INT           NOT NULL DEFAULT 0,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_playlist_item_song (playlist_id, source, song_id),
    KEY idx_user_playlist_item_order (playlist_id, sort_order, id),
    CONSTRAINT fk_user_playlist_item_playlist FOREIGN KEY (playlist_id)
        REFERENCES user_playlist (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Songs inside an imported user playlist';
