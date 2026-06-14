CREATE TABLE IF NOT EXISTS content_article (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  title TEXT NOT NULL,
  digest TEXT,
  content_markdown TEXT,
  content_html TEXT NOT NULL,
  cover_prompt TEXT,
  cover_image_url TEXT,
  topics_json TEXT,
  tags_json TEXT,
  risk_tips_json TEXT,
  model TEXT,
  status TEXT NOT NULL DEFAULT 'DRAFT',
  wechat_media_id TEXT,
  wechat_publish_id TEXT,
  wechat_url TEXT,
  error_message TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_content_article_user_updated
  ON content_article(user_id, updated_at DESC, id DESC);
