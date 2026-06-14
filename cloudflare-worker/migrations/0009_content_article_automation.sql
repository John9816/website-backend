ALTER TABLE content_article ADD COLUMN category TEXT;
ALTER TABLE content_article ADD COLUMN layout_theme TEXT;
ALTER TABLE content_article ADD COLUMN image_mode TEXT;
ALTER TABLE content_article ADD COLUMN automation_json TEXT;

CREATE INDEX IF NOT EXISTS idx_content_article_user_category_updated
  ON content_article(user_id, category, updated_at DESC);
