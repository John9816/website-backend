PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'USER',
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS category (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER,
  name TEXT NOT NULL,
  icon TEXT,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_category_user_sort ON category(user_id, sort_order, id);

CREATE TABLE IF NOT EXISTS nav_link (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER,
  category_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  url TEXT NOT NULL,
  description TEXT,
  icon TEXT,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_link_user_category_sort ON nav_link(user_id, category_id, sort_order, id);

CREATE TABLE IF NOT EXISTS sys_config (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  config_key TEXT NOT NULL UNIQUE,
  config_value TEXT NOT NULL,
  description TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS generated_image (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  prompt TEXT NOT NULL,
  image_url TEXT NOT NULL,
  image_data TEXT,
  model TEXT NOT NULL,
  size TEXT,
  is_shared INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_generated_image_user_created ON generated_image(user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_generated_image_shared ON generated_image(is_shared, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS image_generation_task (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  type TEXT NOT NULL DEFAULT 'generate',
  status TEXT NOT NULL,
  prompt TEXT NOT NULL,
  model TEXT,
  size TEXT,
  n INTEGER NOT NULL DEFAULT 1,
  result_json TEXT,
  timings_json TEXT,
  error_message TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS ai_conversation (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  title TEXT NOT NULL,
  model TEXT NOT NULL,
  last_message_preview TEXT,
  last_message_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ai_conv_user_last ON ai_conversation(user_id, last_message_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS ai_chat_message (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER NOT NULL,
  role TEXT NOT NULL,
  content TEXT NOT NULL,
  model TEXT,
  audio_model TEXT,
  audio_source_url TEXT,
  audio_data TEXT,
  audio_mime_type TEXT,
  audio_external_id TEXT,
  finish_reason TEXT,
  prompt_tokens INTEGER,
  completion_tokens INTEGER,
  total_tokens INTEGER,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ai_msg_conv_id ON ai_chat_message(conversation_id, id);

CREATE TABLE IF NOT EXISTS kb_space (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  description TEXT,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_kb_space_user_sort ON kb_space(user_id, sort_order, id);

CREATE TABLE IF NOT EXISTS kb_doc (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  space_id INTEGER NOT NULL,
  parent_id INTEGER,
  title TEXT NOT NULL,
  content TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (space_id) REFERENCES kb_space(id) ON DELETE CASCADE,
  FOREIGN KEY (parent_id) REFERENCES kb_doc(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_kb_doc_user_space_parent ON kb_doc(user_id, space_id, parent_id, sort_order, id);
CREATE INDEX IF NOT EXISTS idx_kb_doc_user_title ON kb_doc(user_id, title);

CREATE TABLE IF NOT EXISTS kb_tag (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  color TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, name),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS kb_doc_tag (
  doc_id INTEGER NOT NULL,
  tag_id INTEGER NOT NULL,
  PRIMARY KEY (doc_id, tag_id),
  FOREIGN KEY (doc_id) REFERENCES kb_doc(id) ON DELETE CASCADE,
  FOREIGN KEY (tag_id) REFERENCES kb_tag(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS kb_doc_version (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  doc_id INTEGER NOT NULL,
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (doc_id) REFERENCES kb_doc(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_kb_doc_version_doc ON kb_doc_version(doc_id, id DESC);

CREATE TABLE IF NOT EXISTS kb_doc_share (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  doc_id INTEGER NOT NULL UNIQUE,
  user_id INTEGER NOT NULL,
  token TEXT NOT NULL UNIQUE,
  expires_at TEXT,
  view_count INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (doc_id) REFERENCES kb_doc(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS music_play_history (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  source TEXT NOT NULL,
  song_id TEXT NOT NULL,
  name TEXT NOT NULL,
  artist TEXT,
  album TEXT,
  cover_url TEXT,
  duration_sec INTEGER,
  played_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, source, song_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_music_history_user_played ON music_play_history(user_id, played_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS music_favorite (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  source TEXT NOT NULL,
  song_id TEXT NOT NULL,
  name TEXT NOT NULL,
  artist TEXT,
  album TEXT,
  cover_url TEXT,
  duration_sec INTEGER,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, source, song_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_music_favorite_user_created ON music_favorite(user_id, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS music_share (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  source TEXT NOT NULL,
  song_id TEXT NOT NULL,
  name TEXT NOT NULL,
  artist TEXT,
  album TEXT,
  cover_url TEXT,
  duration_sec INTEGER,
  requested_quality TEXT NOT NULL DEFAULT 'standard',
  token TEXT NOT NULL UNIQUE,
  expires_at TEXT,
  view_count INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, source, song_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS user_playlist (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  source TEXT NOT NULL,
  external_id TEXT,
  name TEXT NOT NULL,
  cover_url TEXT,
  track_count INTEGER NOT NULL DEFAULT 0,
  source_url TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_user_playlist_user_created ON user_playlist(user_id, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS user_playlist_item (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  playlist_id INTEGER NOT NULL,
  source TEXT NOT NULL,
  song_id TEXT NOT NULL,
  name TEXT NOT NULL,
  artist TEXT,
  album TEXT,
  cover_url TEXT,
  duration_sec INTEGER,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (playlist_id) REFERENCES user_playlist(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_user_playlist_item_playlist_sort ON user_playlist_item(playlist_id, sort_order, id);

INSERT OR IGNORE INTO sys_config(config_key, config_value, description) VALUES
  ('ai.chat.defaultModel', 'gpt-4.1-mini', 'Default chat model'),
  ('ai.chat.defaultAudioModel', 'gpt-4o-mini-tts', 'Default TTS/audio model'),
  ('ai.chat.models', 'gpt-4.1-mini,gpt-4.1,gpt-4o-mini', 'Comma separated chat models'),
  ('ai.chat.voices', 'alloy|Alloy,verse|Verse,aria|Aria', 'voiceId|Label list'),
  ('image.api.model', 'gpt-image-1', 'Default image model'),
  ('music.play.resolverOrder', 'primary,qq_text,cross_source', 'Compatibility key');
