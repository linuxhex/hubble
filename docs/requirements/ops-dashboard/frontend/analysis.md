# 需求分析(前端工程视角)

> 需求ID(语义key):ops-dashboard | 视角:前端消费方 | 仓库:monitor-dashboard/src

## 需求概述

将运维大盘相关 mock 页面(尤指 `MonitorDashboard.vue`)接入后端真实数据,复用 `AlertOverview.vue` 已有的红黄绿 / 刷新 / echarts 成熟模式,并支持异常下钻(P1)与实时告警推送(P2)。

## 业务背景

- `MonitorDashboard.vue` 当前纯 mock(硬编码服务数 / 响应时间 / 热门服务表,CSS 假趋势图),零接口调用;`timeSlots` 是死代码。
- 项目已有成熟的 AlertOverview / AlertDashboard(红黄绿 `getStatValueClass`、60s 刷新 + visibilitychange、echarts mini 折线 + 阈值线、错误排行、SLS 跳转),是最佳参考与复用源。
- `alert.js` / `errorAnalysis.js` 契约已定义,后端补齐后前端可直接接入。

## 本服务职责

| 职责项 | 说明 |
|---|---|
| 大盘接入 | MonitorDashboard 接 alert-config / alert-data / error-analysis,替换 mock |
| 红黄绿 | 抽取 AlertOverview 的 `getStatValueClass` 为公共 util,卡片 / 行染色 |
| 实时刷新 | 轮询读后端快照(秒级体感)+ echarts 增量更新 + 标签页不可见暂停 |
| 下钻(P1) | 异常行 → 服务日志 → traceId → 上下游,站内跳转 |
| 推送(P2) | 复用 `trace-query.js` 的 SSE 范式订阅告警,闪红 |

## 依赖关系

### 被调用方(我调用谁)

| 服务 | 接口 | 用途 |
|---|---|---|
| 后端 cloudeyes | `/alert-config/*`、`/alert-data/*`、`/error-analysis/*` | 大盘数据 |

## 数据契约

消费后端三组接口(契约由后端实现对齐 `alert.js` / `errorAnalysis.js`)。前端这两个 api 文件已存在且无需改动;MonitorDashboard 直接调用即可。

## 改动范围

- `src/components/MonitorDashboard.vue` 改造:接接口、红黄绿、echarts、刷新、点击下钻入口
- `src/utils/health.js` 新建:红黄绿 status → class / 颜色 映射(抽自 `getStatValueClass`)
- `src/api/monitor.js`(可选):大盘专用聚合查询封装
- (P1)下钻跳转编排 + 复用 `KeywordLogQuery` / `TraceQuery`
- (P2)`src/api/monitor-sse.js` 复用 fetch + ReadableStream 范式

## 风险与注意

- ⚠️ echarts 全量 import 体积偏大:P0 沿用现状(全项目一致),不单独改按需。
- ⚠️ resize 监听泄漏(`SecondChart` / `TrendDashboard` 已知 bug):新组件 handler 提为具名函数,`onBeforeUnmount` 正确移除。
- ⚠️ 401 不跳登录、无 router 守卫:大盘 P0 不处理鉴权跳转(P2 视情况)。
- 💡 `MonitorDashboard.timeSlots` 是死代码,改造时清理或激活为实时时间槽。
