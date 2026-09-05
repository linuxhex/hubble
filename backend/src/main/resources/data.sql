INSERT INTO sys_dict (dict_type, dict_value, dict_label) VALUES
('category', 'performance', '性能监控'),
('category', 'availability', '可用性监控'),
('category', 'business', '业务监控'),
('tag', 'core', '核心服务'),
('tag', 'gateway', '网关层'),
('tag', 'database', '数据库'),
('application', 'user-service', '用户服务'),
('application', 'order-service', '订单服务'),
('application', 'payment-service', '支付服务'),
('application', 'gateway-api', '网关API'),
('application', 'notification-service', '通知服务');

INSERT INTO alert_config (title, description, keyword_template_id, start_time, end_time, collection_interval, alert_threshold, yellow_threshold_ratio, peak_start_time, peak_end_time, peak_alert_threshold, enabled) VALUES
('统计服务错误监控', '监控statistics-server的ERROR日志', 'tpl-statistics-server', '00:00:00', '23:59:59', 60, 2000, 0.75, '09:00:00', '12:00:00', 3000, 1),
('统计TOB错误监控', '监控statistics-tob的ERROR日志', 'tpl-statistics-tob', '00:00:00', '23:59:59', 60, 1200, 0.67, '09:00:00', '12:00:00', 1800, 1),
('交易订单错误监控', '监控trade-order的ERROR日志', 'tpl-trade-order', '00:00:00', '23:59:59', 60, 300, 0.67, '10:00:00', '14:00:00', 450, 1),
('设备维护错误监控', '监控device-maint的ERROR日志', 'tpl-device-maint', '00:00:00', '23:59:59', 60, 200, 0.75, '09:00:00', '18:00:00', 300, 1),
('推送服务错误监控', '监控zdl-push-server的ERROR日志', 'tpl-zdl-push', '00:00:00', '23:59:59', 60, 200, 0.67, '10:00:00', '12:00:00', 300, 1);

INSERT INTO middleware_alert_config (middleware_type, instance_id, metric_name, red_threshold, yellow_threshold, compare_type, enabled) VALUES
('redis', NULL, 'cpuUsage', 80.00, 60.00, '>', 1),
('redis', NULL, 'memoryUsage', 85.00, 70.00, '>', 1),
('redis', NULL, 'connections', 10000.00, 8000.00, '>', 1),
('mysql', NULL, 'cpuUsage', 80.00, 60.00, '>', 1),
('mysql', NULL, 'diskUsage', 85.00, 70.00, '>', 1),
('mysql', NULL, 'connections', 80.00, 60.00, '>', 1),
('rocketmq', NULL, 'messageAccumulation', 100000.00, 50000.00, '>', 1),
('rocketmq', NULL, 'consumeLatency', 60.00, 30.00, '>', 1),
('kafka', NULL, 'lag', 100000.00, 50000.00, '>', 1),
('lindorm', NULL, 'cpuUsage', 80.00, 60.00, '>', 1),
('lindorm', NULL, 'diskUsage', 85.00, 70.00, '>', 1),
('elasticsearch', NULL, 'cpuUsage', 80.00, 60.00, '>', 1),
('elasticsearch', NULL, 'diskUsage', 85.00, 70.00, '>', 1),
('elasticsearch', NULL, 'jvmMemory', 85.00, 75.00, '>', 1),
('oss', NULL, 'errorRate5xx', 1.00, 0.10, '>', 1),
('oss', NULL, 'errorRate4xx', 5.00, 1.00, '>', 1);

-- 预置告警阈值配置
INSERT INTO alert_threshold_config (config_key, config_value, description) VALUES
('degradation_threshold', '220', '接口劣化告警幅度阈值(%)'),
('degradation_min_rt', '300', '接口劣化告警最小RT阈值(ms)，低于此值不告警'),
('traffic_surge_threshold', '200', '流量暴涨告警涨幅阈值(%)'),
('traffic_surge_min_qps', '50', '流量暴涨告警最小QPS，低于此值不告警'),
('minute_red_threshold', '50', '分钟级红盘阈值（错误数）'),
('minute_yellow_threshold', '20', '分钟级黄盘阈值（错误数）')
ON DUPLICATE KEY UPDATE config_value = config_value;
