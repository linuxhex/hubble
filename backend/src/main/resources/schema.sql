CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT NOT NULL AUTO_INCREMENT,
  phone VARCHAR(20),
  nickname VARCHAR(50),
  avatar VARCHAR(500),
  last_login_time DATETIME,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_phone ON sys_user(phone);

CREATE TABLE IF NOT EXISTS alert_config (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(100) NOT NULL,
  description VARCHAR(500),
  keyword_template_id VARCHAR(64) NOT NULL,
  start_time VARCHAR(8) NOT NULL DEFAULT '00:00:00',
  end_time VARCHAR(8) NOT NULL DEFAULT '23:59:59',
  collection_interval INT NOT NULL DEFAULT 60,
  alert_threshold INT NOT NULL DEFAULT 50,
  yellow_threshold_ratio DECIMAL(3,2) NOT NULL DEFAULT 0.50,
  peak_start_time VARCHAR(8),
  peak_end_time VARCHAR(8),
  peak_alert_threshold INT,
  alert_webhook VARCHAR(500),
  enabled TINYINT NOT NULL DEFAULT 1,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS sys_dict (
  id BIGINT NOT NULL AUTO_INCREMENT,
  dict_type VARCHAR(50) NOT NULL,
  dict_value VARCHAR(200) NOT NULL,
  dict_label VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS page_data_cache (
  id BIGINT NOT NULL AUTO_INCREMENT,
  page_key VARCHAR(100) NOT NULL,
  data_key VARCHAR(200) NOT NULL,
  data_content TEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at DATETIME NOT NULL,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_page_data_cache_page_key ON page_data_cache(page_key);
CREATE INDEX IF NOT EXISTS idx_page_data_cache_expires_at ON page_data_cache(expires_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_page_data_cache_keys ON page_data_cache(page_key, data_key);
