-- 日志监控配置表（运维大盘 ops-dashboard）
-- 对齐前端 src/api/alert.js 与 src/components/AlertConfigManagement.vue
CREATE TABLE IF NOT EXISTS alert_config (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    title               VARCHAR(100) NOT NULL                COMMENT '监控标题',
    description         VARCHAR(500) DEFAULT NULL             COMMENT '备注说明',
    keyword_template_id VARCHAR(64)  NOT NULL                COMMENT '关联Milvus SlsKeyword模板ID',
    start_time          VARCHAR(8)   NOT NULL DEFAULT '00:00:00' COMMENT '每日开始时间HH:mm:ss',
    end_time            VARCHAR(8)   NOT NULL DEFAULT '23:59:59' COMMENT '每日结束时间HH:mm:ss',
    collection_interval INT          NOT NULL DEFAULT 60      COMMENT '采集间隔(秒)',
    alert_threshold     INT          NOT NULL DEFAULT 50      COMMENT '告警阈值',
    alert_webhook       VARCHAR(500) DEFAULT NULL             COMMENT '告警通知webhook',
    enabled             TINYINT(1)   NOT NULL DEFAULT 1       COMMENT '是否启用',
    deleted             TINYINT(1)   NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    created_at          DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日志监控配置';
