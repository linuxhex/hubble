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

INSERT INTO alert_config (title, description, keyword_template_id, start_time, end_time, collection_interval, alert_threshold, enabled) VALUES
('用户服务错误监控', '监控用户服务的ERROR级别日志', 'tpl-user-error', '00:00:00', '23:59:59', 60, 50, 1),
('订单服务性能监控', '监控订单服务的响应时间和错误率', 'tpl-order-perf', '08:00:00', '22:00:00', 30, 30, 1),
('支付服务可用性', '监控支付服务的可用性', 'tpl-payment-avail', '00:00:00', '23:59:59', 60, 10, 1),
('网关API流量监控', '监控网关API的请求流量', 'tpl-gateway-traffic', '00:00:00', '23:59:59', 60, 100, 1);
