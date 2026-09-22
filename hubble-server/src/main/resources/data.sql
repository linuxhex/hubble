-- 幂等种子数据：文件库模式下每次启动都会执行，全部走 NOT EXISTS 防重复插入

INSERT INTO sys_dict (dict_type, dict_value, dict_label)
SELECT t.a, t.b, t.c FROM (
  SELECT 'category' AS a, 'performance' AS b, '性能监控' AS c UNION ALL
  SELECT 'category', 'availability', '可用性监控' UNION ALL
  SELECT 'category', 'business', '业务监控' UNION ALL
  SELECT 'tag', 'core', '核心服务' UNION ALL
  SELECT 'tag', 'gateway', '网关层' UNION ALL
  SELECT 'tag', 'database', '数据库' UNION ALL
  SELECT 'sls_tag', 'core', '核心服务' UNION ALL
  SELECT 'sls_tag', 'gateway', '网关层' UNION ALL
  SELECT 'sls_tag', 'database', '数据库' UNION ALL
  SELECT 'application', 'user-service', '用户服务' UNION ALL
  SELECT 'application', 'order-service', '订单服务' UNION ALL
  SELECT 'application', 'payment-service', '支付服务' UNION ALL
  SELECT 'application', 'gateway-api', '网关API' UNION ALL
  SELECT 'application', 'notification-service', '通知服务'
) t
WHERE NOT EXISTS (
  SELECT 1 FROM sys_dict d WHERE d.dict_type = t.a AND d.dict_value = t.b
);

INSERT INTO alert_config (title, description, keyword_template_id, logstore, start_time, end_time, collection_interval, alert_threshold, yellow_threshold_ratio, peak_start_time, peak_end_time, peak_alert_threshold, enabled)
SELECT t.a, t.b, t.c, t.d, t.e, t.f, t.g, t.h, t.i, t.j, t.k, t.l, t.m FROM (
  SELECT '统计服务错误监控' AS a, '监控statistics-server的ERROR日志' AS b, 'tpl-statistics-server' AS c, NULL AS d, '00:00:00' AS e, '23:59:59' AS f, 60 AS g, 2000 AS h, 0.75 AS i, '09:00:00' AS j, '12:00:00' AS k, 3000 AS l, 1 AS m UNION ALL
  SELECT '统计TOB错误监控', '监控statistics-tob的ERROR日志', 'tpl-statistics-tob', NULL, '00:00:00', '23:59:59', 60, 1200, 0.67, '09:00:00', '12:00:00', 1800, 1 UNION ALL
  SELECT '交易订单错误监控', '监控trade-order的ERROR日志', 'tpl-trade-order', NULL, '00:00:00', '23:59:59', 60, 300, 0.67, '10:00:00', '14:00:00', 450, 1 UNION ALL
  SELECT '设备维护错误监控', '监控device-maint的ERROR日志', 'tpl-device-maint', NULL, '00:00:00', '23:59:59', 60, 200, 0.75, '09:00:00', '18:00:00', 300, 1 UNION ALL
  SELECT '推送服务错误监控', '监控zdl-push-server的ERROR日志', 'tpl-zdl-push', NULL, '00:00:00', '23:59:59', 60, 200, 0.67, '10:00:00', '12:00:00', 300, 1 UNION ALL
  SELECT '充电服务错误监控', '监控charge-server的ERROR日志（停充链路/Feign超时）', 'tpl-charge-server', NULL, '00:00:00', '23:59:59', 60, 100, 0.67, '10:00:00', '14:00:00', 150, 1 UNION ALL
  SELECT '指令下发服务错误监控', '监控device-post的ERROR日志（日志在device-post专属库）', 'tpl-device-post', 'device-post', '00:00:00', '23:59:59', 60, 300, 0.67, NULL, NULL, NULL, 1 UNION ALL
  SELECT '桩业务服务错误监控', '监控device-business的ERROR日志（日志在device-business专属库）', 'tpl-device-business', 'device-business', '00:00:00', '23:59:59', 60, 200, 0.67, NULL, NULL, NULL, 1
) t
WHERE NOT EXISTS (
  SELECT 1 FROM alert_config d WHERE d.title = t.a
);

INSERT INTO middleware_alert_config (middleware_type, instance_id, metric_name, red_threshold, yellow_threshold, compare_type, enabled)
SELECT t.a, t.b, t.c, t.d, t.e, t.f, t.g FROM (
  SELECT 'redis' AS a, NULL AS b, 'cpuUsage' AS c, 80.00 AS d, 60.00 AS e, '>' AS f, 1 AS g UNION ALL
  SELECT 'redis', NULL, 'memoryUsage', 85.00, 70.00, '>', 1 UNION ALL
  SELECT 'redis', NULL, 'connections', 10000.00, 8000.00, '>', 1 UNION ALL
  SELECT 'mysql', NULL, 'cpuUsage', 80.00, 60.00, '>', 1 UNION ALL
  SELECT 'mysql', NULL, 'diskUsage', 85.00, 70.00, '>', 1 UNION ALL
  SELECT 'mysql', NULL, 'connections', 80.00, 60.00, '>', 1 UNION ALL
  SELECT 'rocketmq', NULL, 'messageAccumulation', 100000.00, 50000.00, '>', 1 UNION ALL
  SELECT 'rocketmq', NULL, 'consumeLatency', 60.00, 30.00, '>', 1 UNION ALL
  SELECT 'kafka', NULL, 'lag', 100000.00, 50000.00, '>', 1 UNION ALL
  SELECT 'lindorm', NULL, 'cpuUsage', 80.00, 60.00, '>', 1 UNION ALL
  SELECT 'lindorm', NULL, 'diskUsage', 85.00, 70.00, '>', 1 UNION ALL
  SELECT 'elasticsearch', NULL, 'cpuUsage', 80.00, 60.00, '>', 1 UNION ALL
  SELECT 'elasticsearch', NULL, 'diskUsage', 85.00, 70.00, '>', 1 UNION ALL
  SELECT 'elasticsearch', NULL, 'jvmMemory', 85.00, 75.00, '>', 1 UNION ALL
  SELECT 'oss', NULL, 'errorRate5xx', 1.00, 0.10, '>', 1 UNION ALL
  SELECT 'oss', NULL, 'errorRate4xx', 5.00, 1.00, '>', 1
) t
WHERE NOT EXISTS (
  SELECT 1 FROM middleware_alert_config d
  WHERE d.middleware_type = t.a
    AND d.metric_name = t.c
    AND ((d.instance_id IS NULL AND t.b IS NULL) OR d.instance_id = t.b)
);

-- 预置告警阈值配置
INSERT INTO alert_threshold_config (config_key, config_value, description) VALUES
('degradation_threshold', '220', '接口劣化告警幅度阈值(%)'),
('degradation_min_rt', '1000', '接口劣化告警最小RT阈值(ms)，RT低于此值（毫秒级）不告警'),
('traffic_surge_threshold', '200', '流量暴涨告警涨幅阈值(%)'),
('traffic_surge_min_qps', '50', '流量暴涨告警最小QPS，低于此值不告警'),
('minute_red_threshold', '50', '分钟级红盘阈值（错误数）'),
('minute_yellow_threshold', '20', '分钟级黄盘阈值（错误数）'),
('minute_red_multiplier', '6', '分钟级红盘动态阈值倍率（均值×此倍数）'),
('minute_yellow_multiplier', '3', '分钟级黄盘动态阈值倍率（均值×此倍数）'),
('minute_red_floor', '5', '分钟级红盘动态阈值下限'),
('minute_yellow_floor', '2', '分钟级黄盘动态阈值下限'),
('dynamic_red_sigma', '3', '7日动态红盘sigma倍数（均值+此值×标准差）'),
('dynamic_yellow_sigma', '2', '7日动态黄盘sigma倍数（均值+此值×标准差）'),
('dynamic_red_mean_mult', '5', '7日动态红盘均值倍数下限（均值×此值）'),
('dynamic_yellow_mean_mult', '3', '7日动态黄盘均值倍数下限（均值×此值）'),
('dynamic_red_floor', '10', '7日动态红盘绝对下限'),
('dynamic_yellow_floor', '5', '7日动态黄盘绝对下限'),
('alert_cooldown_hours', '24', '告警防抖冷却时间(小时)，同一告警在此时间内不重复'),
('consecutive_red_count', '3', '连续红盘次数达到此值才触发钉钉告警'),
('min_request_count', '10', '劣化/暴涨统计最小请求数，低于此值不参与排名'),
('mw_yoy_surge_threshold', '300', '中间件告警同比涨幅阈值(%)，当前值相对昨天同一5分钟窗口'),
('mw_yoy_abs_floor', '30', '中间件告警同比绝对值下限，当前值低于此值不告警'),
('consecutive_recover_count', '3', '恢复通知：连续正常采集达到此次数才推送已恢复通知')
ON DUPLICATE KEY UPDATE config_value = config_value;

-- 历史默认值自愈迁移：仅当配置仍是旧默认值时升级为新默认值，不覆盖页面自定义值
UPDATE alert_threshold_config SET config_value = '1000' WHERE config_key = 'degradation_min_rt' AND config_value = '300';

-- 依赖服务RT巡检口径对齐ARMS：涨幅500%→100%（识别低RT高流量变慢），流量门槛10次/5分钟→3000次/最近1分钟
UPDATE alert_threshold_config SET config_value = '100' WHERE config_key = 'dependency_rt_surge_ratio' AND config_value = '500';
UPDATE alert_threshold_config SET config_value = '3000' WHERE config_key = 'dependency_rt_min_count' AND config_value = '10';
UPDATE alert_threshold_config SET description = '依赖服务RT环比涨幅阈值(%)，最近5分钟均值对比前5分钟均值（对齐ARMS口径）' WHERE config_key = 'dependency_rt_surge_ratio' AND description = '依赖服务 RT 环比涨幅阈值(%)，达到即告警';
UPDATE alert_threshold_config SET description = '依赖服务高流量门槛(次/最近1分钟)，达到后免RT绝对下限，涨幅达标即告警' WHERE config_key = 'dependency_rt_min_count' AND description = '依赖服务调用次数下限(次/窗口)，低于不告警';
UPDATE alert_threshold_config SET description = '依赖服务RT绝对下限(ms)，仅对未达高流量门槛的组合生效，防微秒级抖动误报' WHERE config_key = 'dependency_rt_floor_ms' AND description = '依赖服务 RT 绝对下限(ms)，低于不告警';

-- 预置钉钉机器人演示数据
INSERT INTO dingtalk_robot (name, webhook, secret, remark, enabled, created_at, updated_at)
SELECT t.a, t.b, t.c, t.d, t.e, NOW(), NOW() FROM (
  SELECT '运维群机器人' AS a, 'https://oapi.dingtalk.com/robot/send?access_token=demo-token-ops-001' AS b, 'SEC-demo-ops-secret-key' AS c, '通知到运维群，接收服务器告警和故障通知' AS d, 1 AS e UNION ALL
  SELECT '技术告警群机器人', 'https://oapi.dingtalk.com/robot/send?access_token=demo-token-tech-002', 'SEC-demo-tech-secret-key', '通知到技术告警群，接收中间件和服务异常告警', 1 UNION ALL
  SELECT '业务监控群机器人', 'https://oapi.dingtalk.com/robot/send?access_token=demo-token-biz-003', NULL, '通知到业务监控群，接收业务指标异常告警', 0
) t
WHERE NOT EXISTS (
  SELECT 1 FROM dingtalk_robot d WHERE d.name = t.a
);

-- 预置业务链路示例数据（充电业务场景，变量占位符 {var} 由链路查询页填充）
INSERT INTO business_trace (name, description, category)
SELECT t.a, t.b, t.c FROM (
  SELECT '充电订单链路' AS a, '用户扫码启动充电到订单结算完成的端到端链路（示例数据，可在链路配置中编辑）' AS b, '充电业务' AS c UNION ALL
  SELECT '用户登录链路', '用户登录鉴权到结果通知的调用链路（示例数据，可在链路配置中编辑）', '用户业务'
) t
WHERE NOT EXISTS (
  SELECT 1 FROM business_trace d WHERE d.name = t.a
);

-- 顶级节点（parent_id 为空，链路查询只查顶级节点，子节点用于展开下钻）
INSERT INTO trace_node (trace_id, parent_id, name, description, sls_logstore, query_template, node_order)
SELECT bt.id, NULL, t.a, t.b, t.c, t.d, t.e
FROM (
  SELECT '充电订单链路' AS trace_name, '扫码启动充电' AS a, 'charge-server 收到扫码启动充电请求，输出订单号与桩号' AS b, 'all' AS c, 'message: "{order_no}" and __tag__:_container_name_: charge-server' AS d, 1 AS e UNION ALL
  SELECT '充电订单链路', '订单创建落库', 'order-service 创建充电订单并落库', 'all', 'message: "{order_no}" and __tag__:_container_name_: order-service', 2 UNION ALL
  SELECT '充电订单链路', '充电指令下发', 'device-post 向充电桩下发开始充电指令', 'device-post', 'message: "{order_no}" and "startCharging"', 3 UNION ALL
  SELECT '充电订单链路', '桩端响应确认', '充电桩确认开始充电的回报日志', 'all', 'message: "{order_no}" and "chargeConfirm"', 4 UNION ALL
  SELECT '充电订单链路', '订单结算完成', '充电完成后订单结算与计费', 'all', 'message: "{order_no}" and "settle"', 5 UNION ALL
  SELECT '用户登录链路', '登录请求受理', 'gateway-api 收到用户登录请求', 'all', 'message: "{user_id}" and __tag__:_container_name_: gateway-api', 1 UNION ALL
  SELECT '用户登录链路', '用户鉴权校验', 'user-service 校验用户凭证并签发令牌', 'all', 'message: "{user_id}" and "token" and __tag__:_container_name_: user-service', 2 UNION ALL
  SELECT '用户登录链路', '登录结果通知', 'notification-service 推送登录成功通知', 'all', 'message: "{user_id}" and "login success" and __tag__:_container_name_: notification-service', 3
) t
JOIN business_trace bt ON bt.name = t.trace_name AND bt.deleted = 0
WHERE NOT EXISTS (
  SELECT 1 FROM trace_node d
  WHERE d.trace_id = bt.id AND d.name = t.a AND d.parent_id IS NULL AND d.deleted = 0
);

-- 子节点示例（挂在"充电指令下发"下，演示链路下钻）
INSERT INTO trace_node (trace_id, parent_id, name, description, sls_logstore, query_template, node_order)
SELECT bt.id, pn.id, t.a, t.b, t.c, t.d, t.e
FROM (
  SELECT '充电订单链路' AS trace_name, '充电指令下发' AS parent_name, '指令下发重试' AS a, '指令下发失败后的重试记录' AS b, 'device-post' AS c, 'message: "{order_no}" and "retry" and "startCharging"' AS d, 1 AS e
) t
JOIN business_trace bt ON bt.name = t.trace_name AND bt.deleted = 0
JOIN trace_node pn ON pn.trace_id = bt.id AND pn.name = t.parent_name AND pn.deleted = 0
WHERE NOT EXISTS (
  SELECT 1 FROM trace_node d
  WHERE d.trace_id = bt.id AND d.parent_id = pn.id AND d.name = t.a AND d.deleted = 0
);
