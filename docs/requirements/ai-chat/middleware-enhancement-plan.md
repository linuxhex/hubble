# 中间件监控增强方案

## 背景

当前中间件监控只展示基础集群信息，存在以下问题：
1. **无告警能力**：中间件指标（CPU、堆积、延迟等）超过阈值时无法告警
2. **缺少 Top 分析**：无法快速定位问题 Topic、大 Key、慢查询等

## 需求分析

### 1. 中间件告警阈值

| 中间件 | 告警指标 | 红盘阈值 | 粉盘阈值 | 说明 |
|--------|----------|----------|----------|------|
| Redis | CPU 使用率 | > 80% | > 60% | 实例级别 |
| Redis | 内存使用率 | > 85% | > 70% | 实例级别 |
| Redis | 连接数 | > 10000 | > 8000 | 实例级别 |
| MySQL | CPU 使用率 | > 80% | > 60% | 实例级别 |
| MySQL | 磁盘使用率 | > 85% | > 70% | 实例级别 |
| MySQL | 连接数 | > 80% 最大连接 | > 60% 最大连接 | 实例级别 |
| RocketMQ | 消息堆积量 | > 100000 | > 50000 | 实例级别 |
| RocketMQ | 消费延迟 | > 60s | > 30s | 实例级别 |
| Kafka | 消息堆积(Lag) | > 100000 | > 50000 | 实例级别 |
| Lindorm | CPU 使用率 | > 80% | > 60% | 实例级别 |
| Lindorm | 磁盘使用率 | > 85% | > 70% | 实例级别 |
| Elasticsearch | CPU 使用率 | > 80% | > 60% | 集群级别 |
| Elasticsearch | 磁盘使用率 | > 85% | > 70% | 集群级别 |
| Elasticsearch | JVM 内存 | > 85% | > 75% | 节点级别 |
| OSS | 5xx 错误率 | > 1% | > 0.1% | Bucket 级别 |
| OSS | 4xx 错误率 | > 5% | > 1% | Bucket 级别 |

### 2. Top 指标展示

| 中间件 | Top 指标 | 维度 | 说明 |
|--------|----------|------|------|
| RocketMQ | Top 10 堆积 Topic | Topic | 按消息堆积量排序 |
| RocketMQ | Top 10 生产 TPS Topic | Topic | 按生产速度排序 |
| Kafka | Top 10 Lag Partition | Partition | 按消费延迟排序 |
| Kafka | Top 10 生产 TPS Topic | Topic | 按生产速度排序 |
| Redis | Top 10 Big Keys | Key | 按内存占用排序 |
| Redis | Top 10 慢命令 | Command | 按执行耗时排序 |
| MySQL | Top 10 慢查询 | SQL | 按执行耗时排序 |
| MySQL | Top 10 大表 | Table | 按数据量排序 |
| Lindorm | Top 10 读写表 | Table | 按 QPS 排序 |
| Elasticsearch | Top 10 大索引 | Index | 按存储大小排序 |

## 技术方案

### 1. 告警配置表设计

新增 `middleware_alert_config` 表：

```sql
CREATE TABLE middleware_alert_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    middleware_type VARCHAR(50) NOT NULL,  -- redis/mysql/rocketmq/kafka/lindorm/elasticsearch/oss
    instance_id VARCHAR(100),              -- 实例 ID（空表示全局默认）
    metric_name VARCHAR(100) NOT NULL,     -- 指标名（cpu_usage/memory_usage/accumulation 等）
    red_threshold DOUBLE NOT NULL,         -- 红盘阈值
    yellow_threshold DOUBLE NOT NULL,      -- 粉盘阈值
    compare_type VARCHAR(20) DEFAULT '>',  -- 比较方式：> / < / >= / <=
    enabled BOOLEAN DEFAULT TRUE,
    alert_webhook VARCHAR(500),            -- 钉钉 webhook（可选）
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_type_instance (middleware_type, instance_id),
    INDEX idx_metric (metric_name)
);
```

### 2. 告警检查逻辑

在 `MiddlewareMonitorService` 中增加告警检查：

```java
// 每次查询中间件数据后，检查是否触发告警
public void checkMiddlewareAlerts(String middlewareType, String instanceId, Map<String, Double> metrics) {
    List<MiddlewareAlertConfig> configs = alertConfigMapper.selectByTypeAndInstance(middlewareType, instanceId);
    for (MiddlewareAlertConfig config : configs) {
        Double value = metrics.get(config.getMetricName());
        if (value == null) continue;
        
        boolean isRed = compare(value, config.getRedThreshold(), config.getCompareType());
        boolean isYellow = compare(value, config.getYellowThreshold(), config.getCompareType());
        
        if (isRed) {
            triggerAlert(config, instanceId, "red", value);
        } else if (isYellow) {
            triggerAlert(config, instanceId, "yellow", value);
        }
    }
}
```

### 3. Top 指标查询

#### RocketMQ Top Topic

CloudMonitor 支持按 Topic 维度查询堆积：

```java
public List<RocketmqTopicVO> rocketmqTopTopics(String instanceId, int topN) {
    // 查询 acs_mq 命名空间，按 Topic 维度
    String dimensions = "[{\"instanceId\":\"" + instanceId + "\"}]";
    // 需要先获取实例下所有 Topic 列表（通过 RocketMQ SDK 或配置）
    // 然后逐个查询堆积量，排序返回 Top N
}
```

#### Kafka Top Partition

```java
public List<KafkaPartitionVO> kafkaTopPartitions(String instanceId, String topic, int topN) {
    // 查询 acs_kafka 命名空间，按 Partition 维度
    // 需要遍历所有 Partition 查询 Lag
}
```

#### Redis Big Keys

```java
public List<RedisBigKeyVO> redisBigKeys(String instanceId, int topN) {
    // 使用 Redis SDK 的 SCAN + MEMORY USAGE 命令
    // 或者使用阿里云 Redis 的 hotkey 分析功能
}
```

### 4. 前端展示

#### 告警状态展示

在中间件表格中增加告警状态列：

```vue
<el-table-column label="状态" width="100">
  <template #default="{ row }">
    <el-tag v-if="row.alertLevel === 'red'" type="danger">红盘</el-tag>
    <el-tag v-else-if="row.alertLevel === 'yellow'" type="warning">粉盘</el-tag>
    <el-tag v-else type="success">正常</el-tag>
  </template>
</el-table-column>
```

#### Top 指标展示

新增 Tab 或展开区域展示 Top 指标：

```vue
<!-- RocketMQ Top Topic -->
<el-collapse>
  <el-collapse-item title="Top 10 堆积 Topic">
    <el-table :data="rocketmqTopTopics">
      <el-table-column prop="topic" label="Topic" />
      <el-table-column prop="accumulation" label="堆积量" />
      <el-table-column prop="sendTps" label="生产 TPS" />
      <el-table-column prop="consumeTps" label="消费 TPS" />
    </el-table>
  </el-collapse-item>
</el-collapse>
```

## 实现优先级

### P0（必须实现）
1. 中间件告警配置表 + 基础告警逻辑
2. Redis/MySQL 告警（已有真实数据）
3. 前端告警状态展示

### P1（建议实现）
4. RocketMQ/Kafka 告警
5. RocketMQ Top Topic 展示
6. Kafka Top Partition 展示

### P2（可选实现）
7. Redis Big Keys / 慢命令
8. MySQL 慢查询 / 大表
9. Lindorm/Elasticsearch Top 指标

## 数据准确性保证

1. **告警阈值**：使用 CloudMonitor 真实数据，5 分钟缓存
2. **Top 指标**：直接查询 CloudMonitor 按维度数据，确保与阿里云控制台一致
3. **告警触发**：连续 2 次超过阈值才触发（避免瞬时波动误报）
4. **告警去重**：同一实例同一指标 1 小时内不重复告警

## 文件清单

### 新增
- `entity/MiddlewareAlertConfig.java`
- `mapper/MiddlewareAlertConfigMapper.java`
- `service/MiddlewareAlertService.java`
- `controller/MiddlewareAlertController.java`
- SQL: `middleware_alert_config` 表

### 修改
- `service/MiddlewareMonitorService.java`：增加告警检查逻辑
- `components/MiddlewareDashboard.vue`：增加告警状态列 + Top 指标展示
- `api/middleware.js`：增加 Top 指标 API

## 工作量评估

- P0：2-3 天
- P1：2-3 天
- P2：3-4 天

总计：7-10 天
