INSERT INTO sys_config(config_key, config_value, description) VALUES
  ('image.persist.remote-url-mode', 'proxy', 'Store upstream image URLs in R2')
ON CONFLICT(config_key) DO UPDATE SET
  config_value = 'proxy',
  description = 'Store upstream image URLs in R2',
  updated_at = CURRENT_TIMESTAMP;
