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
('用户服务错误监控', '监控用户服务的ERROR级别日志', 'tpl-user-error', '00:00:00', '23:59:59', 60, 50, 0.50, '09:00:00', '12:00:00', 80, 1),
('订单服务性能监控', '监控订单服务的响应时间和错误率', 'tpl-order-perf', '08:00:00', '22:00:00', 30, 30, 0.60, '10:00:00', '14:00:00', 50, 1),
('支付服务可用性', '监控支付服务的可用性', 'tpl-payment-avail', '00:00:00', '23:59:59', 60, 10, 0.40, '09:00:00', '21:00:00', 20, 1),
('网关API流量监控', '监控网关API的请求流量', 'tpl-gateway-traffic', '00:00:00', '23:59:59', 60, 100, 0.50, '08:00:00', '20:00:00', 200, 1),
('订单服务错误监控', '监控订单服务的ERROR日志', 'tpl-order-error', '00:00:00', '23:59:59', 60, 40, 0.50, '10:00:00', '14:00:00', 60, 1),
('支付网关监控', '监控支付网关的异常请求', 'tpl-payment-gateway', '00:00:00', '23:59:59', 30, 20, 0.50, '09:00:00', '21:00:00', 35, 1),
('用户认证服务', '监控认证服务的异常', 'tpl-auth-error', '00:00:00', '23:59:59', 60, 30, 0.50, '08:00:00', '22:00:00', 50, 1),
('库存服务监控', '监控库存服务的异常操作', 'tpl-inventory', '00:00:00', '23:59:59', 60, 15, 0.60, '09:00:00', '18:00:00', 25, 1),
('消息推送服务', '监控推送服务的失败率', 'tpl-notification', '00:00:00', '23:59:59', 60, 60, 0.50, '10:00:00', '12:00:00', 100, 1),
('设备服务监控', '监控设备服务的连接异常', 'tpl-device', '00:00:00', '23:59:59', 60, 25, 0.50, '08:00:00', '20:00:00', 40, 1);
