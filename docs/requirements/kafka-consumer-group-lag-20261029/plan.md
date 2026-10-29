# 实现计划：Kafka 消费组级堆积监控告警

## 任务拆分

### T1. 后端数据采集 — `MiddlewareMonitorService.kafkaConsumerGroups()`

文件：`hubble-server/src/main/java/com/ykc/hubble/service/MiddlewareMonitorService.java`

- 新增字段：`kafkaConsumerGroupsCache` / `kafkaConsumerGroupsCacheTime`（沿用 5 分钟 TTL 模式）
- 新增方法 `kafkaConsumerGroups()`：
  - PromQL：`sum by (instanceId, consumerGroup, ins_name, tag_env) (AliyunKafka_message_accumulation)`
  - 数据源：`grafanaClient.getAliyunDsUid()`
  - 用 `queryInstant` 拿到带 label 的行（label key 为 `instanceId`/`consumerGroup`/`ins_name`/`tag_env`）
  - 每行构造 map：`instanceId`、`instanceName`(=ins_name)、`consumerGroup`、`lag`(=value)、`tagEnv`(=tag_env)
  - 按 lag 降序排序
  - 缓存；空时返回空列表（不造假数据）
  - 异常时返回空列表并 log

### T2. 后端告警 — `MiddlewareAlertService`

文件：`hubble-server/src/main/java/com/ykc/hubble/service/MiddlewareAlertService.java`

- `scheduledAlertCheck()` 中 kafka 分支：在现有实例 `checkAlerts("kafka", instances)` 之后，额外调用新方法 `checkKafkaConsumerGroupAlerts()`
- 新增 `checkKafkaConsumerGroupAlerts()`：
  - `List<Map<String,Object>> groups = monitorService.kafkaConsumerGroups()`
  - 过滤：保留 `tag_env=prod` 或 `ins_name` 以 `prod-` 开头；排除 `consumerGroup` 以 `spark-kafka-source` 开头
  - 对每个 group：从 `middleware_alert_config` 取 `middleware_type=kafka AND metric_name=consumerGroupLag` 的配置（缓存）
  - value = `lag`；红盘阈值 1000000、粉盘 100000；`isTriggered`/`isRedLevel` 沿用现有
  - 红盘：`alertKey = "mw:kafka:cg:" + instanceId + ":" + consumerGroup`；24h 防抖；调用 `pushKafkaConsumerGroupAlert()` 推钉钉+SSE+AI 调查
  - 黄盘：同比过滤沿用现有逻辑（resolveYesterdayValue 暂无消费组同比，跳过过滤直接告警粉盘——与现有"昨天值不可得时跳过过滤防漏报"一致）
  - 给 group map 写 `alertLevel`/`alertDetails` 供页面展示
- 新增 `pushKafkaConsumerGroupAlert(group, details)`：仿 `pushMiddlewareAlert`，`serviceName = "kafka/" + consumerGroup`，`alertType = "kafka消费组堆积"`，`alertSource = "MW"`，`severity = "ERROR"`，`alertValue = "consumerGroupLag=" + lag`

### T3. 告警配置 — `data.sql`

文件：`hubble-server/src/main/resources/data.sql`

在现有 kafka lag 行后增加：
```sql
SELECT 'kafka', NULL, 'consumerGroupLag', 1000000.00, 100000.00, '>', 1 UNION ALL
```

### T4. API — `MiddlewareController`

文件：`hubble-server/src/main/java/com/ykc/hubble/controller/MiddlewareController.java`

新增：
```java
@GetMapping("/kafka/consumer-groups")
public Result<List<Map<String, Object>>> kafkaConsumerGroups() {
    return Result.success(middlewareMonitorService.kafkaConsumerGroups());
}
```

### T5. 前端 API — `middleware.js`

文件：`hubble-web/src/api/middleware.js`

新增：
```js
export function getKafkaConsumerGroups() {
  return request.get('/middleware/kafka/consumer-groups')
}
```

### T6. 前端表格 — `MiddlewareDashboard.vue`

文件：`hubble-web/src/components/MiddlewareDashboard.vue`

- import `getKafkaConsumerGroups`
- 新增 ref `kafkaConsumerGroupsData`
- kafka 分支 fetch：实例后追加 `const cgRes = await getKafkaConsumerGroups(); kafkaConsumerGroupsData.value = cgRes.data || []`
- 模板：在「实例基础信息」表与「Top Partitions」之间插入「消费组堆积」表
  - 列：告警点 / 消费组 / 实例 / Lag / 环境
  - Lag 用 `Math.round(num(row.lag)).toLocaleString()`
  - 告警点 `alert-dot-{alertLevel}`

## 推演关键点

- `consumerGroupLag` 配置在已部署的库中不存在（data.sql NOT EXISTS 仅启动时插一次）→ 需在 controller 或 schema 层保证升级时插入，或文档注明手动 INSERT。**采用**：data.sql NOT EXISTS 兜底 + 启动时若缺失由 `checkKafkaConsumerGroupAlerts` 取不到配置则跳过（不报错，仅 log warn）。
- 同比过滤：消费组无昨天值（resolveYesterdayValue 不支持消费组维度），按现有规则"昨天值不可得跳过过滤"处理，粉盘直接告警。
- 消费组名含特殊字符（如 `spark-kafka-source-...-driver-0`）→ alertKey 用 consumerGroup 原值，钉钉 markdown 转义由现有发送逻辑处理。
- 缓存共享：`kafkaConsumerGroups()` 被 API 和告警巡检共用，5 分钟 TTL，巡检触发时刷新缓存。
