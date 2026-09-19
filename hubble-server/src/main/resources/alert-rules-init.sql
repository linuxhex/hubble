-- ============================================
-- Hubble 告警规则初始化脚本
-- 包含：SLS关键字模板 + 告警配置
-- ============================================

-- ============================================
-- 第一部分：SLS关键字模板
-- ============================================

-- 1. 数据库相关错误
INSERT INTO SlsKeyword (id, `desc`, keywords, application, logstore, tags) VALUES
('kw-db-error-001', '数据库调用错误', '数据库调用错误 OR DatabaseError OR SQLException OR "数据库异常"', 'order-foundation-prod', 'app-log', 'database,error'),
('kw-db-error-002', '数据库连接池耗尽', 'Connection pool exhausted OR "无法获取连接" OR "连接池满"', 'all', 'app-log', 'database,pool'),
('kw-db-error-003', '数据库死锁', 'Deadlock found OR "锁等待超时" OR Lock wait timeout', 'all', 'app-log', 'database,deadlock'),
('kw-db-slow-001', '慢SQL查询', 'Slow query OR "执行时间超过" OR slowSql', 'all', 'app-log', 'database,slow');

-- 2. 异常类告警
INSERT INTO SlsKeyword (id, `desc`, keywords, application, logstore, tags) VALUES
('kw-exception-001', '日期时间解析异常', 'DateTimeParseException OR LocalDateTimeExtUtil.parse fail', 'all', 'app-log', 'exception,parse'),
('kw-exception-002', '空指针异常', 'NullPointerException OR NPE', 'all', 'app-log', 'exception,null'),
('kw-exception-003', '订单服务异常', 'OrderMos 异常 OR OrderException OR "订单处理失败"', 'order-foundation-prod', 'app-log', 'order,exception'),
('kw-exception-004', '支付异常', 'PayException OR "支付失败" OR "支付超时"', 'charge-server', 'app-log', 'pay,exception'),
('kw-exception-005', '充电交易异常', 'ChargeException OR "充电订单异常" OR "充电启动失败"', 'charge-server', 'app-log', 'charge,exception'),
('kw-exception-006', 'HTTP调用异常', 'HttpClientException OR RestTemplateException OR "远程调用失败"', 'all', 'app-log', 'http,exception'),
('kw-exception-007', 'Redis异常', 'RedisException OR JedisException OR "Redis连接失败"', 'all', 'app-log', 'redis,exception'),
('kw-exception-008', 'MQ消息异常', 'MQException OR "消息发送失败" OR "消息消费失败"', 'all', 'app-log', 'mq,exception');

-- 3. 业务逻辑告警
INSERT INTO SlsKeyword (id, `desc`, keywords, application, logstore, tags) VALUES
('kw-biz-001', '订单创建失败', '"创建订单失败" OR "订单保存失败" OR OrderCreateException', 'order-foundation-prod', 'app-log', 'order,fail'),
('kw-biz-002', '支付回调失败', '"支付回调处理失败" OR PayCallbackException', 'charge-server', 'app-log', 'pay,callback'),
('kw-biz-003', '库存扣减失败', '"库存不足" OR "扣减库存失败" OR InventoryException', 'all', 'app-log', 'inventory,fail'),
('kw-biz-004', '用户认证失败', '"登录失败" OR "认证失败" OR AuthException', 'base-server', 'app-log', 'auth,fail'),
('kw-biz-005', '设备通信异常', '"设备离线" OR "设备通信失败" OR DeviceException', 'all', 'app-log', 'device,exception');

-- 4. 性能告警
INSERT INTO SlsKeyword (id, `desc`, keywords, application, logstore, tags) VALUES
('kw-perf-001', '接口响应超时', '"接口超时" OR "响应超时" OR TimeoutException OR Read timed out', 'all', 'app-log', 'performance,timeout'),
('kw-perf-002', 'GC停顿过长', '"GC pause" OR "Full GC" OR "Stop the world"', 'all', 'app-log', 'performance,gc'),
('kw-perf-003', '线程池满', '"线程池已满" OR "拒绝任务" OR RejectedExecutionException', 'all', 'app-log', 'performance,thread'),
('kw-perf-004', '内存溢出', 'OutOfMemoryError OR OOM OR "内存不足"', 'all', 'app-log', 'performance,memory');

-- 5. 安全告警
INSERT INTO SlsKeyword (id, `desc`, keywords, application, logstore, tags) VALUES
('kw-security-001', 'SQL注入尝试', 'SQL injection OR "非法SQL" OR SqlInjectionException', 'all', 'app-log', 'security,injection'),
('kw-security-002', 'XSS攻击尝试', 'XSS attack OR "脚本注入" OR CrossSiteScripting', 'all', 'app-log', 'security,xss'),
('kw-security-003', '暴力破解尝试', '"密码错误次数过多" OR "账号锁定" OR BruteForce', 'base-server', 'app-log', 'security,bruteforce');

-- 6. 系统级告警
INSERT INTO SlsKeyword (id, `desc`, keywords, application, logstore, tags) VALUES
('kw-system-001', '磁盘空间不足', '"磁盘空间不足" OR "No space left" OR DiskFullException', 'all', 'app-log', 'system,disk'),
('kw-system-002', 'CPU使用率过高', '"CPU使用率超过" OR "CPU负载过高"', 'all', 'app-log', 'system,cpu'),
('kw-system-003', '服务启动失败', '"服务启动失败" OR "应用启动异常" OR StartupException', 'all', 'app-log', 'system,startup');

-- ============================================
-- 第二部分：告警配置（引用上面的关键字模板）
-- ============================================

-- 数据库告警
INSERT INTO alert_config (title, description, keyword_template_id, start_time, end_time, collection_interval, alert_threshold, yellow_threshold_ratio, enabled, deleted) VALUES
('数据库调用错误告警', '监控数据库调用错误，5分钟内>=10次触发告警', 'kw-db-error-001', '00:00:00', '23:59:59', 60, 10, 0.50, 1, 0),
('数据库连接池告警', '监控数据库连接池耗尽，5分钟内>=3次触发告警', 'kw-db-error-002', '00:00:00', '23:59:59', 60, 3, 0.50, 1, 0),
('数据库死锁告警', '监控数据库死锁，5分钟内>=1次触发告警', 'kw-db-error-003', '00:00:00', '23:59:59', 60, 1, 0.50, 1, 0),
('慢SQL告警', '监控慢SQL查询，5分钟内>=20次触发告警', 'kw-db-slow-001', '00:00:00', '23:59:59', 60, 20, 0.50, 1, 0);

-- 异常告警
INSERT INTO alert_config (title, description, keyword_template_id, start_time, end_time, collection_interval, alert_threshold, yellow_threshold_ratio, enabled, deleted) VALUES
('日期解析异常告警', '监控DateTimeParseException等日期解析错误，5分钟内>=5次触发告警', 'kw-exception-001', '00:00:00', '23:59:59', 60, 5, 0.50, 1, 0),
('空指针异常告警', '监控NullPointerException，5分钟内>=10次触发告警', 'kw-exception-002', '00:00:00', '23:59:59', 60, 10, 0.50, 1, 0),
('订单服务异常告警', '监控OrderMos等订单服务异常，5分钟内>=5次触发告警', 'kw-exception-003', '00:00:00', '23:59:59', 60, 5, 0.50, 1, 0),
('支付异常告警', '监控支付相关异常，5分钟内>=3次触发告警', 'kw-exception-004', '00:00:00', '23:59:59', 60, 3, 0.50, 1, 0),
('充电交易异常告警', '监控充电交易异常，5分钟内>=5次触发告警', 'kw-exception-005', '00:00:00', '23:59:59', 60, 5, 0.50, 1, 0),
('HTTP调用异常告警', '监控远程HTTP调用异常，5分钟内>=10次触发告警', 'kw-exception-006', '00:00:00', '23:59:59', 60, 10, 0.50, 1, 0),
('Redis异常告警', '监控Redis相关异常，5分钟内>=5次触发告警', 'kw-exception-007', '00:00:00', '23:59:59', 60, 5, 0.50, 1, 0),
('MQ消息异常告警', '监控消息队列异常，5分钟内>=5次触发告警', 'kw-exception-008', '00:00:00', '23:59:59', 60, 5, 0.50, 1, 0);

-- 业务告警
INSERT INTO alert_config (title, description, keyword_template_id, start_time, end_time, collection_interval, alert_threshold, yellow_threshold_ratio, enabled, deleted) VALUES
('订单创建失败告警', '监控订单创建失败，5分钟内>=10次触发告警', 'kw-biz-001', '00:00:00', '23:59:59', 60, 10, 0.50, 1, 0),
('支付回调失败告警', '监控支付回调处理失败，5分钟内>=5次触发告警', 'kw-biz-002', '00:00:00', '23:59:59', 60, 5, 0.50, 1, 0),
('库存扣减失败告警', '监控库存扣减失败，5分钟内>=10次触发告警', 'kw-biz-003', '00:00:00', '23:59:59', 60, 10, 0.50, 1, 0),
('用户认证失败告警', '监控登录认证失败，5分钟内>=20次触发告警', 'kw-biz-004', '00:00:00', '23:59:59', 60, 20, 0.50, 1, 0),
('设备通信异常告警', '监控设备离线和通信失败，5分钟内>=10次触发告警', 'kw-biz-005', '00:00:00', '23:59:59', 60, 10, 0.50, 1, 0);

-- 性能告警
INSERT INTO alert_config (title, description, keyword_template_id, start_time, end_time, collection_interval, alert_threshold, yellow_threshold_ratio, enabled, deleted) VALUES
('接口超时告警', '监控接口响应超时，5分钟内>=10次触发告警', 'kw-perf-001', '00:00:00', '23:59:59', 60, 10, 0.50, 1, 0),
('GC停顿告警', '监控GC停顿过长，5分钟内>=3次触发告警', 'kw-perf-002', '00:00:00', '23:59:59', 60, 3, 0.50, 1, 0),
('线程池满告警', '监控线程池满载，5分钟内>=5次触发告警', 'kw-perf-003', '00:00:00', '23:59:59', 60, 5, 0.50, 1, 0),
('内存溢出告警', '监控OOM内存溢出，1次即触发告警', 'kw-perf-004', '00:00:00', '23:59:59', 60, 1, 0.50, 1, 0);

-- 安全告警
INSERT INTO alert_config (title, description, keyword_template_id, start_time, end_time, collection_interval, alert_threshold, yellow_threshold_ratio, enabled, deleted) VALUES
('SQL注入告警', '监控SQL注入尝试，1次即触发告警', 'kw-security-001', '00:00:00', '23:59:59', 60, 1, 0.50, 1, 0),
('XSS攻击告警', '监控XSS攻击尝试，1次即触发告警', 'kw-security-002', '00:00:00', '23:59:59', 60, 1, 0.50, 1, 0),
('暴力破解告警', '监控暴力破解尝试，5分钟内>=10次触发告警', 'kw-security-003', '00:00:00', '23:59:59', 60, 10, 0.50, 1, 0);

-- 系统告警
INSERT INTO alert_config (title, description, keyword_template_id, start_time, end_time, collection_interval, alert_threshold, yellow_threshold_ratio, enabled, deleted) VALUES
('磁盘空间告警', '监控磁盘空间不足，5分钟内>=3次触发告警', 'kw-system-001', '00:00:00', '23:59:59', 60, 3, 0.50, 1, 0),
('CPU使用率告警', '监控CPU使用率过高，5分钟内>=5次触发告警', 'kw-system-002', '00:00:00', '23:59:59', 60, 5, 0.50, 1, 0),
('服务启动失败告警', '监控服务启动失败，1次即触发告警', 'kw-system-003', '00:00:00', '23:59:59', 60, 1, 0.50, 1, 0);
