ALTER TABLE users
    ADD COLUMN register_ip VARCHAR(45) DEFAULT NULL AFTER avatar_url,
    ADD COLUMN last_login_at DATETIME DEFAULT NULL AFTER register_ip,
    ADD COLUMN last_login_ip VARCHAR(45) DEFAULT NULL AFTER last_login_at,
    ADD COLUMN login_count INT NOT NULL DEFAULT 0 AFTER last_login_ip;
