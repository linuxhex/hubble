# 需求分析(后端 cloudeyes 视角)

> 需求ID(语义key):ops-dashboard | 视角:后端主服务 | 仓库:monitor-dashboard/backend

## 需求概述

为运维大盘提供后端数据能力:补齐前端已定义的 `alert-config` / `alert-data` / `error-analysis` 三组接口契约,数据源基于阿里云 SLS(复用 `SlsQueryClient`),支持准实时快照缓存与红黄绿健康度评估,使前端 AlertOverview / AlertDashboard / MonitorDashboard 等大盘页面接入真实数据。

## 业务背景

- 项目处于「前端先行、后端待补」状态:前端已实现完整大盘 UI 与 API 契约(`alert.js` / `errorAnalysis.js`),并落地了 AlertOverview / AlertDashboard 成熟组件(红黄绿染色、60s 自动刷新、echarts、错误排行、SLS 跳转),但后端这三组接口完全缺失(grep `alert/threshold` 零命中)。
- `MonitorDashboard.vue` 为纯 mock,大盘无法展示真实数据。
- 需后端按前端契约实现接口,数据统一走 SLS 日志数量模型(非 ARMS 的 RT/QPS)。

## 本服务职责

| 职责项 | 说明 |
|---|---|
| 监控项配置 | alert-config:监控项 CRUD(服务/logstore/查询关键字/阈值/时间窗/启停) |
| 实时数据 | alert-data:按监控项统计实时日志数、今日峰值/均值、告警计数、明细时序 |
| 错误分析 | error-analysis:错误类型排行(Top N + 占比 + 环比)、错误趋势 |
| 准实时刷新 | Caffeine 快照 + `@Scheduled` 定时增量拉 SLS(日志数 3~5s、聚合 30s) |
| 健康度评估 | 基于 `alertThreshold` 计算红/黄/绿/灰 status(worst-wins 聚合) |
| 下钻(P1) | 服务日志查询(SLS all + 容器名 + traceId) |
| 推送(P2) | SSE 实时告警 + SLS webhook 入口 |

## 依赖关系

### 调用方(谁调用我)

| 调用方 | 接口 | 用途 |
|---|---|---|
| 前端 MonitorDashboard / AlertOverview / AlertDashboard | `/alert-config/*`、`/alert-data/*`、`/error-analysis/*` | 大盘展示与下钻 |

### 被调用方(我调用谁)

| 服务 | 能力 | 用途 |
|---|---|---|
| 阿里云 SLS | `SlsQueryClient.countLogstore` / `queryLogstore` | 日志计数与原文查询 |

## 数据契约(严格对齐前端 `src/api/alert.js` 与 `errorAnalysis.js`)

### alert-config(监控项配置)

| 接口 | 方法 | 说明 |
|---|---|---|
| `/alert-config/query` | GET | 分页查询监控项(current,size,enabled) |
| `/alert-config/{id}` | GET | 详情 |
| `/alert-config` | POST/PUT/DELETE | 增改删;`/enable`、`/disable` 启停 |

### alert-data(实时数据)

| 接口 | 方法 | 关键返回字段 |
|---|---|---|
| `/alert-data/query/statistics/{configId}` | GET | currentLogCount, todayMax, todayAvg, alertThreshold, todayAlertCount, detailStatistics |
| `/alert-data/query` | GET | 时序明细 records[{collectedAt, logCount}] |

### error-analysis(错误分析)

| 接口 | 方法 | 关键返回字段 |
|---|---|---|
| `/error-analysis/query/error-types/{configId}` | GET | [{typeName, count, percentage, growthRate, category, firstSeenAt, sampleLog}] |
| `/error-analysis/query/error-types-trend/{configId}` | GET | {timestamps, series} |
| `/error-analysis/query/error-trend/{configId}` | GET | 错误趋势 |

## 改动范围

- 新增 `alert_config` 表 + `AlertConfig` 实体 / Mapper / Service / Controller
- 新增 `AlertDataService`(实时统计,读快照)+ `AlertDataController`
- 新增 `ErrorAnalysisService` + `ErrorAnalysisController`
- 新增 `CaffeineConfig`、`MonitorSnapshotJob`(`@Scheduled`)、`SnapshotCache`
- 新增 `HealthEvaluator`(红黄绿)
- `pom.xml` 引入 caffeine
- `application.yml` 增加 `monitor` 配置段(刷新间隔 / 默认阈值 / 缓存 TTL)
- AuthFilter 白名单按需放行(SSE/webhook),其余大盘接口走默认 JWT
- (P1)`/alert-data/service-logs` 下钻接口
- (P2)`/alert-data/sse` + webhook 入口

## 风险与注意

- ⚠️ SLS 单 project 写死(`SlsClientFactory` 单例):`ctp-*` / `device-*` 多 project 在 P0 不支持,P1 下钻做兜底降级。
- ⚠️ `countLogstore` 高频调用:必须走快照缓存,前端只读缓存;P0 限流熔断简化为「查询失败用上一份旧缓存」。
- ⚠️ `AuthFilter` 不注入用户上下文:大盘 P0 不做按用户鉴权,接口走默认 JWT 通过即可。
- ⚠️ 时间戳:`SlsQueryClient` 要求秒级时间戳,定时任务窗口计算须注意单位(避免传毫秒)。
- 💡 ARMS:P0 不引入(前端契约无 RT/QPS),P1 再评估是否补精度。
