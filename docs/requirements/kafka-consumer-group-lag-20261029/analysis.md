# 需求分析：Kafka 消费组级堆积监控告警

## 背景与根因

线上 `tp_order_data_group` 消费组在阿里云 Kafka 控制台显示堆积，但 Hubble 未检测告警。

排查结论（已通过 Grafana/Prometheus 验证）：

1. **实例级监控把消费组维度聚合掉了** — `MiddlewareMonitorService.kafkaInstances()` 用 `sum by (instanceId) (AliyunKafka_message_accumulation)`，丢弃 `consumerGroup` label。某消费组堆积数亿，实例总和被其他组摊薄，或实例整体红盘但定位不到是哪个组。

2. **Top Partitions 数据源不对** — `kafkaTopPartitions()` 用 `kafka_consumer_fetch_manager_records_lag`（JVM 侧 consumer lag，按 application+topic），实测当前全为 0；阿里云侧消费组堆积（`AliyunKafka_message_accumulation` 带 `consumerGroup` label）从未用于展示。

3. **告警链路缺消费组维度** — `checkAlerts` 只评估实例级 `lag`（阈值 10w/5w），消费组从未进入告警评估。

### Prometheus 实测数据（aliyun-ds `l9II0lm4z`）

`AliyunKafka_message_accumulation` label：`instanceId`、`consumerGroup`、`ins_name`、`tag_env`、`regionId` 等。

当前 TOP 消费组堆积：
- `gp_emp_yems_server` ≈ 3.4 亿（prod-kafka）
- `gp_emp_digital_server` ≈ 2.2 亿（prod-kafka）
- `cid_link_server` ≈ 1.4 亿（prod-kafka）
- `tp_order_data_group` 生产实例仅 24 条（但控制台口径不同，可能为分区级 offset lag）

阿里云 Prometheus 仅 `AliyunKafka_message_accumulation` 一个堆积指标，无分区级 lag 指标。

## 决策结论

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 阈值 | 红 100万 / 粉 10万（独立 metric `consumerGroupLag`） | 只对真正大积压告警，当前约命中 3-5 个组，避免刷屏 |
| 告警范围 | 只告警生产（`tag_env=prod` 或 `ins_name` 以 `prod-` 开头），排除 `spark-kafka-source-*` 临时组 | 页面展示全部，钉钉只盯生产真实消费组 |
| 数据源 | `AliyunKafka_message_accumulation` 按 `(instanceId, consumerGroup)` | 阿里云官方消费组堆积指标 |
| 与实例级关系 | 解耦，实例级 `sum by (instanceId)` 保持不变 | 实例总览仍有用，互不影响 |

## 方案

1. `MiddlewareMonitorService.kafkaConsumerGroups()`：按 `(instanceId, consumerGroup, ins_name, tag_env)` 查堆积，5 分钟缓存，按 lag 降序，返回全部（页面用）。
2. `MiddlewareAlertService`：kafka 巡检额外拉消费组列表 → 只对生产+非 spark 组评估 `consumerGroupLag` 阈值；红盘推钉钉 + AI 调查；`alertKey = mw:kafka:cg:{instanceId}:{consumerGroup}`；`serviceName = kafka/{consumerGroup}`；黄盘同比过滤沿用现有逻辑。
3. `data.sql` 新增 `kafka/consumerGroupLag` 配置（红 1000000 / 粉 100000）。
4. `GET /api/middleware/kafka/consumer-groups` API。
5. 前端 Kafka tab 增加「消费组堆积」表格。

## 不做

- 不改实例级 `sum by (instanceId)` 聚合（实例总览仍有价值）
- 不删 Top Partitions（保留展示，但非主告警源）
- 不引入新表（复用 `middleware_alert_config`）
