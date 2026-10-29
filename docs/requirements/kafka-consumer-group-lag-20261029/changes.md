# 改动简述：Kafka 消费组级堆积监控告警

## 问题
Hubble 只按 `instanceId` 聚合 `AliyunKafka_message_accumulation`，消费组维度被抹平，导致 `tp_order_data_group` 等消费组积压无法检测告警。

## 改动

### 后端
- `MiddlewareMonitorService.java`：新增 `kafkaConsumerGroups()`，按 `(instanceId, consumerGroup, ins_name, tag_env)` 查 `AliyunKafka_message_accumulation`，5 分钟缓存，按 lag 降序，返回全部消费组（页面用）。
- `MiddlewareAlertService.java`：`scheduledAlertCheck` kafka 实例巡检后追加 `checkKafkaConsumerGroupAlerts()`；只评估生产环境（`tag_env=prod` 或 `ins_name` 以 `prod-` 开头）、排除 `spark-kafka-source-*` 临时组；`consumerGroupLag` 阈值红 100万/粉 10万；红盘 24h 防抖推钉钉+SSE+AI 调查（`serviceName=kafka/{consumerGroup}`）。
- `MiddlewareController.java`：新增 `GET /api/middleware/kafka/consumer-groups`。
- `data.sql`：新增 `kafka/consumerGroupLag` 配置（红 1000000 / 粉 100000）。

### 前端
- `middleware.js`：新增 `getKafkaConsumerGroups()`。
- `MiddlewareDashboard.vue`：Kafka tab 在实例表与 Top Partitions 间增加「消费组堆积」表（告警点/消费组/实例/Lag/环境）。

## 验证
- 后端 `mvn compile` BUILD SUCCESS
- 前端 `npm run build` 成功
