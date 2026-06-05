ALTER TABLE users ADD COLUMN credits INTEGER NOT NULL DEFAULT 0;

ALTER TABLE image_generation_task ADD COLUMN credit_cost INTEGER NOT NULL DEFAULT 0;
ALTER TABLE image_generation_task ADD COLUMN credit_refunded INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS user_check_in (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  check_in_date TEXT NOT NULL,
  reward_credits INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, check_in_date),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_user_check_in_user_date ON user_check_in(user_id, check_in_date DESC);

INSERT INTO sys_config(config_key, config_value, description) VALUES
  ('image.generate.credit_cost', '1', 'Credits charged per generated image'),
  ('user.checkin.reward_credits', '5', 'Credits rewarded for one daily check-in'),
  ('user.register.initial_credits', '10', 'Credits granted to each newly registered account')
ON CONFLICT(config_key) DO UPDATE SET
  description = excluded.description,
  updated_at = CURRENT_TIMESTAMP;
