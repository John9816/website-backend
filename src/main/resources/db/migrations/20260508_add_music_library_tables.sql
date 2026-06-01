CREATE TABLE IF NOT EXISTS music_play_history (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    user_id       BIGINT        NOT NULL,
    source        VARCHAR(20)   NOT NULL,
    song_id       VARCHAR(100)  NOT NULL,
    name          VARCHAR(300)  NOT NULL,
    artist        VARCHAR(300)  DEFAULT NULL,
    album         VARCHAR(300)  DEFAULT NULL,
    cover_url     VARCHAR(1000) DEFAULT NULL,
    duration_sec  INT           DEFAULT NULL,
    played_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_music_history_user_song (user_id, source, song_id),
    KEY idx_music_history_user_played (user_id, played_at, id),
    CONSTRAINT fk_music_history_user FOREIGN KEY (user_id)
        REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per-user music play history';

CREATE TABLE IF NOT EXISTS music_favorite (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    user_id       BIGINT        NOT NULL,
    source        VARCHAR(20)   NOT NULL,
    song_id       VARCHAR(100)  NOT NULL,
    name          VARCHAR(300)  NOT NULL,
    artist        VARCHAR(300)  DEFAULT NULL,
    album         VARCHAR(300)  DEFAULT NULL,
    cover_url     VARCHAR(1000) DEFAULT NULL,
    duration_sec  INT           DEFAULT NULL,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_music_favorite_user_song (user_id, source, song_id),
    KEY idx_music_favorite_user_created (user_id, created_at, id),
    CONSTRAINT fk_music_favorite_user FOREIGN KEY (user_id)
        REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per-user favorite songs';
