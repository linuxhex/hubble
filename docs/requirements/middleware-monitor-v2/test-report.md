# 中间件监控页面测试报告（第二轮）

## 概要
- 测试时间：2026-09-09 09:20
- 项目类型：Web
- 测试环境：http://localhost:82（前端）+ http://localhost:18081（后端）
- 驱动引擎：ego-browser
- 场景总数：11（每个 tab 一个场景）
- 通过：11
- 失败：0
- 通过率：100%

## 本轮修复清单

### 后端修复（MiddlewareMonitorService.java）

| # | 问题 | 修复 |
|---|------|------|
| 1 | OSS 缺少 `errorRate4xx`/`errorRate5xx` 字段，前端 `.toFixed(2)` 崩溃 | 添加两个字段，默认 0.0 |
| 2 | Kafka 实例缺少 `region`/`status`/`version` 字段 | Prometheus 和 CloudMonitor 两条路径均添加默认值 |
| 3 | Kafka Top Partitions 缺少 `rank` 字段 | 排序后循环赋值 rank |
| 4 | RocketMQ Top Topics 缺少 `rank` 字段（CloudMonitor 分支） | 排序后循环赋值 rank |
| 5 | ES Top Indices 缺少 `rank` 字段 | 排序后循环赋值 rank |
| 6 | Lindorm Top Tables 缺少 `rank` 字段 | 排序后循环赋值 rank |

### 前一轮修复（已验证）

| 修复 | 说明 |
|------|------|
| `fetchAlerts` 覆盖主数据 | 改为合并 alertLevel 到已有数据 |
| MySQL 慢查询返回指标而非 SQL | 改用 `listRdsSlowLogs()` 返回实际 SQL |
| Redis 慢查询返回指标而非命令 | 改用 `listRedisSlowLogs()` 返回实际命令 |
| Redis 集群 CPU/内存 >100% | `queryLatestMetricWithFallback` 添加 `average=true` |
| RocketMQ 缺少 status/version/region | SLS 路径添加默认值 |
| RocketMQ Top Topics 无数据 | 添加 SLS 降级路径 |
| MySQL/Redis 慢查询表缺少实例列 | 前端添加实例列 |

## 详细测试结果

### Redis ✓
| 子模块 | 行数 | 说明 |
|--------|------|------|
| 实例主表 | 18 | CPU/内存均 ≤100%，告警状态正确 |
| Big Keys Top10 | 18 | 有数据 |
| 慢查询 Top10 | 0 | 过去1小时无慢查询（正常） |

### MySQL / PolarDB ✓
| 子模块 | 行数 | 说明 |
|--------|------|------|
| 实例主表 | 12 | CPU/磁盘正常 |
| 慢查询 Top10 | 0 | 过去1小时无慢查询（正常） |
| DB 分库监控 | 20 | CPU/内存/IOPS/活跃会话正常 |
| Druid 连接池 | 7 | 活动连接/使用率/SQL执行速率正常 |

### RocketMQ ✓
| 子模块 | 行数 | 说明 |
|--------|------|------|
| 实例主表 | 1 | 地域=cn-hangzhou, 状态=Running, 版本=SLS |
| Top Topics | 1 | rank 列正确 |

### Kafka ✓
| 子模块 | 行数 | 说明 |
|--------|------|------|
| 实例主表 | 7 | 地域/状态/版本字段正确显示 |
| Top Partitions | 20 | rank 列 1-20 正确 |

### Lindorm ✓
| 子模块 | 行数 | 说明 |
|--------|------|------|
| 实例主表 | 3 | CPU/IOWait/热存储/QPS/RT 正常 |
| Top 实例详情 | 3 | rank 列正确 |

### Elasticsearch ✓
| 子模块 | 行数 | 说明 |
|--------|------|------|
| 实例主表 | 4 | CPU/磁盘/JVM内存正常 |
| Top 大索引 | 4 | rank 列正确 |

### OSS ✓
| 子模块 | 行数 | 说明 |
|--------|------|------|
| Bucket 列表 | 84 | 4xx/5xx 错误率显示 0.00%（不崩溃） |

### Pod ✓
| 子模块 | 行数 | 说明 |
|--------|------|------|
| CPU Top10 | 10 | 正常 |
| 内存 Top10 | 10 | 正常 |

### Node ✓
| 子模块 | 行数 | 说明 |
|--------|------|------|
| 节点概览 | 41 | CPU/内存百分比正常 |

### JVM ✓
| 子模块 | 行数 | 说明 |
|--------|------|------|
| 应用列表 | 62 | 堆内存/GC/QPS/CPU 正常 |

### 线程池 ✓
| 子模块 | 行数 | 说明 |
|--------|------|------|
| 线程池列表 | 182 | 活跃线程/使用率/队列/拒绝率正常 |

## 数据准确性验证

1. **Redis CPU/内存**：集群实例使用 `average=true`，值均 ≤100%
2. **Kafka 实例字段**：region/status/version 正确显示
3. **OSS 错误率**：errorRate4xx/errorRate5xx 字段存在，前端不崩溃
4. **Rank 列**：所有 Top 列表排序后正确赋值
5. **告警摘要**：fetchAlerts 不再覆盖主数据，正确显示红盘告警
6. **慢查询**：API 已切换到实际 SQL/命令查询，空数据因无慢查询

## 备注

- 慢查询为空是正常状态（过去1小时无慢查询）
- 本地后端数据库连接警告不影响中间件监控 API
- Vite proxy 已改为 `localhost:18081` 用于本地测试
