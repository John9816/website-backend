-- Personal navigation site schema
-- Execute once on the target MySQL instance.
-- Tables will also be auto-created/updated by Hibernate (ddl-auto: update),
-- this script is for manual provisioning or fresh installs.

CREATE DATABASE IF NOT EXISTS website
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE website;

-- Application users (admin is seeded on first startup with default admin/admin123)
CREATE TABLE IF NOT EXISTS `user` (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    username    VARCHAR(50)  NOT NULL,
    email       VARCHAR(100) DEFAULT NULL,
    password    VARCHAR(255) NOT NULL COMMENT 'BCrypt hash',
    role        VARCHAR(20)  NOT NULL DEFAULT 'USER',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    UNIQUE KEY uk_user_email (email)
 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Application users';

-- Link categories
CREATE TABLE IF NOT EXISTS category (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       DEFAULT NULL,
    name        VARCHAR(100) NOT NULL,
    icon        VARCHAR(255) DEFAULT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_category_user_sort (user_id, sort_order, id),
    KEY idx_category_sort (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Link categories';

-- Navigation links
CREATE TABLE IF NOT EXISTS nav_link (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       DEFAULT NULL,
    category_id  BIGINT       NOT NULL,
    name         VARCHAR(100) NOT NULL,
    url          VARCHAR(500) NOT NULL,
    description  VARCHAR(500) DEFAULT NULL,
    icon         VARCHAR(500) DEFAULT NULL,
    sort_order   INT          NOT NULL DEFAULT 0,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_link_user_category_sort (user_id, category_id, sort_order, id),
    KEY idx_link_category (category_id),
    KEY idx_link_sort (sort_order),
    CONSTRAINT fk_link_category FOREIGN KEY (category_id)
        REFERENCES category (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Navigation links';

-- Generic key-value system config (image API baseUrl/key/model, etc.)
-- Seeded on first boot by DataInitializer; editable at runtime via /api/admin/configs.
CREATE TABLE IF NOT EXISTS sys_config (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    config_key    VARCHAR(100)  NOT NULL,
    config_value  VARCHAR(2000) NOT NULL,
    description   VARCHAR(500)  DEFAULT NULL,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Generic system key-value config';

-- Per-user image generation history. Populated on every successful
-- POST /api/admin/image/generate; listed via GET /api/admin/image/history.
CREATE TABLE IF NOT EXISTS generated_image (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    user_id     BIGINT        NOT NULL,
    prompt      VARCHAR(2000) NOT NULL,
    image_url   VARCHAR(1000) NOT NULL,
    image_data  LONGTEXT      DEFAULT NULL,
    model       VARCHAR(100)  NOT NULL,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_gen_img_user (user_id, created_at),
    CONSTRAINT fk_gen_img_user FOREIGN KEY (user_id)
        REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Generated image history per user';

-- Per-user AI chat conversations. Populated via /api/user/ai/conversations
-- and used to list a user's chat history.
CREATE TABLE IF NOT EXISTS ai_conversation (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    user_id               BIGINT       NOT NULL,
    title                 VARCHAR(120) NOT NULL,
    model                 VARCHAR(100) NOT NULL,
    last_message_preview  VARCHAR(500) DEFAULT NULL,
    last_message_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_conv_user_last (user_id, last_message_at, id),
    CONSTRAINT fk_ai_conv_user FOREIGN KEY (user_id)
        REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI chat conversations per user';

-- Per-message AI conversation history. Populated only after a successful
-- upstream chat completion so stored history stays consistent.
CREATE TABLE IF NOT EXISTS ai_chat_message (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id    BIGINT       NOT NULL,
    role               VARCHAR(20)  NOT NULL,
    content            LONGTEXT     NOT NULL,
    model              VARCHAR(100) DEFAULT NULL,
    audio_model        VARCHAR(100) DEFAULT NULL,
    audio_source_url   VARCHAR(1000) DEFAULT NULL,
    audio_data         LONGTEXT     DEFAULT NULL,
    audio_mime_type    VARCHAR(100) DEFAULT NULL,
    audio_external_id  VARCHAR(120) DEFAULT NULL,
    finish_reason      VARCHAR(50)  DEFAULT NULL,
    prompt_tokens      INT          DEFAULT NULL,
    completion_tokens  INT          DEFAULT NULL,
    total_tokens       INT          DEFAULT NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_msg_conv_id (conversation_id, id),
    CONSTRAINT fk_ai_msg_conversation FOREIGN KEY (conversation_id)
        REFERENCES ai_conversation (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI chat messages';

-- Per-user music play history. Authenticated GET /api/v1/music/play requests
-- upsert the song and refresh played_at so each user keeps only recent songs.
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

-- Per-user favorite songs. Same song is deduplicated by user/source/song_id.
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

-- Per-user public music shares. Stores a stable token plus track snapshot so
-- clients can open /api/public/music/share/{token} without needing auth.
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

-- Optional: seed sample data
-- INSERT INTO category (name, icon, sort_order) VALUES
--     ('Dev Tools', 'tools', 1),
--     ('AI',        'robot', 2),
--     ('News',      'rss',   3);
