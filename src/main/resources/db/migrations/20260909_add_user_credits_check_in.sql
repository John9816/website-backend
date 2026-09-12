ALTER TABLE users ADD COLUMN credits INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS user_check_in (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    check_in_date DATE NOT NULL,
    reward_credits INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_user_check_in_date (user_id, check_in_date),
    KEY idx_user_check_in_user_date (user_id, check_in_date),
    CONSTRAINT fk_user_check_in_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_config (config_key, config_value, description)
VALUES ('image.generate.credit_cost', '1', 'Credits charged per generated image')
ON DUPLICATE KEY UPDATE description = VALUES(description);

INSERT INTO sys_config (config_key, config_value, description)
VALUES ('user.checkin.reward_credits', '5', 'Credits rewarded for one daily check-in')
ON DUPLICATE KEY UPDATE description = VALUES(description);

INSERT INTO sys_config (config_key, config_value, description)
VALUES ('user.register.initial_credits', '10', 'Credits granted to each newly registered account')
ON DUPLICATE KEY UPDATE description = VALUES(description);
