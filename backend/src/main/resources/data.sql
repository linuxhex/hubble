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
