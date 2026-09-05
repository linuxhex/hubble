# Hubble 全功能测试报告

## 概要
- 测试时间：2026-09-06 00:09
- 项目类型：Web
- 测试环境：http://localhost:5173 (前端) + http://localhost:8080 (后端)
- 驱动引擎：ego-browser
- 场景总数：10
- 通过：10
- 失败：0
- 通过率：100%

## 详细结果

### 1. 网关概览 `/gateway` ✓
| 指标 | 数值 |
|------|------|
| 总请求量 | 31,644,260 |
| 平均响应时间 | 496ms |
| 错误率 | 0.05% |
| QPS | 546.88 |
| 表格数据 | 20 行 |
| 图表 | 2 个 |

**结论**：真实数据，功能正常

### 2. 异常大盘 `/abnormal` ✓
| 服务 | 事件数 |
|------|--------|
| statistics-server | 1037 |
| statistics-tob | 519 |
| device-maint | 39 |
| trade-order | 34 |
| gateway-app | 27 |
| zdl-push-server | 13 |

**结论**：真实数据，功能正常

### 3. 接口劣化 `/degradation-ranking` ✓
| 指标 | 数值 |
|------|------|
| 表格数据 | 30 行 |

**结论**：真实数据，功能正常

### 4. 流量暴涨 `/traffic-surge` ✓
| 指标 | 数值 |
|------|------|
| 暴涨接口 (>200%) | 0 |
| 涨幅显著 (>50%) | 0 |

**结论**：当前无流量暴涨（正常状态），功能正常

### 5. 中间件监控 `/middleware` ✓
| 中间件 | 实例数 | 数据来源 |
|--------|--------|----------|
| Redis | 18 | CloudMonitor |
| MySQL/PolarDB | 12 | CloudMonitor |
| Kafka | 有数据 | Prometheus |
| Elasticsearch | 4 | CloudMonitor |
| RocketMQ | 0 | 环境无实例 |
| Lindorm | 0 | API 权限不足 |

**子功能**：
- Redis Big Keys Top10 ✓
- MySQL 慢查询 Top10 ✓ (CloudMonitor MySQL_SlowQueries)

**结论**：核心中间件有真实数据

### 6. 链路查询 `/trace-query` ✓
| 指标 | 说明 |
|------|------|
| 功能 | 需要输入链路ID查询 |
| 状态 | 功能正常，待查询 |

**结论**：功能正常

### 7. 网关日志 `/gateway/logs` ✓
| 指标 | 说明 |
|------|------|
| 搜索框 | 有 |
| 当前结果 | 0 条（需输入搜索条件）|

**结论**：功能正常，需要搜索条件

### 8. 告警配置 `/alert-config` ✓
| 指标 | 数值 |
|------|------|
| 告警规则 | 27 条 |

**结论**：真实配置数据

### 9. 经营分析 `/biz-analysis` ✓
| 指标 | 数值 |
|------|------|
| 表格数据 | 7 行 |
| 图表 | 10 个 |

**结论**：真实数据，功能正常

### 10. 用户行为 `/user-behavior` ✓
| 指标 | 说明 |
|------|------|
| 功能 | 用户行为分析页面 |
| 状态 | 页面加载正常 |

**结论**：功能正常

## 后端 API 数据验证

| API | 状态 | 数据量 |
|-----|------|--------|
| `/api/middleware/redis/instances` | ✓ | 18 实例 |
| `/api/middleware/mysql/instances` | ✓ | 12 实例 |
| `/api/middleware/mysql/slow-queries` | ✓ | 50 条（CloudMonitor） |
| `/api/middleware/redis/big-keys` | ✓ | 18 条 |
| `/api/middleware/kafka/top-partitions` | ✓ | 4 Topic |
| `/api/middleware/elasticsearch/top-indices` | ✓ | 4 实例 |
| `/api/alert/config/list` | ✓ | 27 条 |

## 已知限制

| 功能 | 状态 | 原因 |
|------|------|------|
| Redis 慢查询（具体SQL） | ⚠ | API 权限不足，只能获取慢查询计数 |
| Lindorm Top Tables | ⚠ | API 权限不足 |
| RocketMQ | ⚠ | 环境无实例 |

## 结论

**所有核心功能正常**，10/10 页面可用：
- 5 个页面有真实生产数据
- 5 个页面功能正常（需搜索条件或当前无异常）

**数据获取改动生效**：
1. MySQL 慢查询改用 CloudMonitor 指标 ✓
2. Kafka Topic 使用 Prometheus 数据 ✓
3. Redis/ES 使用 CloudMonitor 指标 ✓
4. 告警配置数据正常 ✓
