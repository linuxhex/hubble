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

-- 中间件告警配置表
CREATE TABLE IF NOT EXISTS middleware_alert_config (
  id BIGINT NOT NULL AUTO_INCREMENT,
  middleware_type VARCHAR(50) NOT NULL COMMENT '中间件类型: redis/mysql/rocketmq/kafka/lindorm/elasticsearch/oss',
  instance_id VARCHAR(100) COMMENT '实例ID，空表示全局默认',
  metric_name VARCHAR(100) NOT NULL COMMENT '指标名: cpu_usage/memory_usage/accumulation等',
  red_threshold DECIMAL(10,2) NOT NULL COMMENT '红盘阈值',
  yellow_threshold DECIMAL(10,2) NOT NULL COMMENT '粉盘阈值',
  compare_type VARCHAR(20) NOT NULL DEFAULT '>' COMMENT '比较方式: > / < / >= / <=',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_middleware_alert_type ON middleware_alert_config(middleware_type);
CREATE INDEX IF NOT EXISTS idx_middleware_alert_metric ON middleware_alert_config(metric_name);

-- 钉钉机器人配置（支持多个机器人通知到多个群）
CREATE TABLE IF NOT EXISTS dingtalk_robot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL COMMENT '机器人名称',
  webhook VARCHAR(500) NOT NULL COMMENT '钉钉机器人Webhook地址',
  secret VARCHAR(200) COMMENT '加签密钥（选填）',
  remark VARCHAR(200) COMMENT '备注（如通知到运维群）',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

-- 告警规则与机器人绑定（多对多：一条规则可通知多个群）
CREATE TABLE IF NOT EXISTS alert_config_robot (
  alert_config_id BIGINT NOT NULL,
  robot_id BIGINT NOT NULL,
  PRIMARY KEY (alert_config_id, robot_id)
);

-- 服务告警阈值配置（接口劣化/流量暴涨/红黄盘等阈值统一管理）
CREATE TABLE IF NOT EXISTS alert_threshold_config (
  id BIGINT NOT NULL AUTO_INCREMENT,
  config_key VARCHAR(100) NOT NULL UNIQUE COMMENT '配置键',
  config_value VARCHAR(200) NOT NULL COMMENT '配置值',
  description VARCHAR(200) COMMENT '说明',
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

-- 业务链路（trace 管理）
CREATE TABLE IF NOT EXISTS business_trace (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(200) NOT NULL COMMENT '业务名称',
  description VARCHAR(500) COMMENT '业务描述',
  category VARCHAR(100) COMMENT '业务分类',
  deleted INT DEFAULT 0 COMMENT '删除标记',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

-- 服务每日负载数据（每2天采集一次，存储最近一年）
CREATE TABLE IF NOT EXISTS service_load_daily (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app_name VARCHAR(100) NOT NULL COMMENT '应用名称',
  pid VARCHAR(100) NOT NULL COMMENT 'ARMS应用PID',
  stat_date DATE NOT NULL COMMENT '统计日期',
  avg_cpu DECIMAL(8,2) COMMENT '平均CPU使用率(%)',
  max_cpu DECIMAL(8,2) COMMENT '最大CPU使用率(%)',
  avg_memory DECIMAL(8,2) COMMENT '平均内存使用率(%)',
  max_memory DECIMAL(8,2) COMMENT '最大内存使用率(%)',
  gc_count INT COMMENT 'GC次数',
  gc_time DECIMAL(10,2) COMMENT 'GC耗时(ms)',
  max_qps DECIMAL(10,2) COMMENT '最高QPS',
  avg_rt DECIMAL(10,2) COMMENT '平均响应时间(ms)',
  total_count BIGINT COMMENT '总调用次数',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_service_load_daily ON service_load_daily(app_name, stat_date);
CREATE INDEX IF NOT EXISTS idx_service_load_daily_date ON service_load_daily(stat_date);
