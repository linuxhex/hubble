# 运维大盘(ops-dashboard)实现计划 — 前端

**目标:** 将 `MonitorDashboard.vue`(纯 mock)接入后端 `alert-config` / `alert-data` / `error-analysis` 三组接口,复用 AlertOverview 已有的红黄绿 / 刷新 / echarts 模式,使大盘展示真实数据。

**架构:** MonitorDashboard onMounted 拉监控项列表 → 对每项并发取 statistics + 时序 → 卡片红黄绿染色 + echarts 趋势 + 秒级轮询读缓存;异常项点击下钻(P1)。

**技术栈:** Vue3 + Element Plus + Pinia + echarts(全量 import,沿用现状)

---

## 文件清单

| 文件 | 操作 | 职责 |
|---|---|---|
| `src/utils/health.js` | 新增 | 红黄绿 status → class/颜色(抽自 AlertOverview `getStatValueClass`) |
| `src/components/MonitorDashboard.vue` | 修改 | 接接口、红黄绿、echarts、刷新、下钻入口 |
| `src/api/monitor.js` | 新增(可选) | 大盘聚合查询封装(复用 alert.js / errorAnalysis.js) |

> `src/api/alert.js` / `errorAnalysis.js` 契约已对齐,不改。

---

## 任务拆分

### 任务 1:红黄绿 util

**文件:** 新增 `src/utils/health.js`

**实现要点:**
- `getHealthStatus(value, threshold)`:≥threshold → danger(红);≥threshold*0.5 → warning(黄);有值 → success(绿);无数据 → info(灰)。
- 导出 `STATUS_CLASS` / `STATUS_COLOR` 映射,供卡片与表格行复用。
- 与 AlertOverview/AlertDashboard 的 `getStatValueClass` 行为一致(后续可让那两个组件也改用此 util,本次不动)。

### 任务 2:MonitorDashboard.vue 改造

**文件:** 修改 `src/components/MonitorDashboard.vue`

**实现要点:**
- 删除 mock 数据(`hotServices` / `timeSlots` 死代码);onMounted 调 `getAlertConfigList({enabled:true, current:1, size:N})` 取启用的监控项。
- 「服务概览」卡片改为:对每监控项并发 `getAlertStatistics(id, '15m')` → 用 `health.js` 染色 + 显示 currentLogCount/threshold;顶部汇总(总数/异常数/告警数)用 worst-wins 聚合。
- 「热门服务」表格:列 = 监控项标题 / 当前日志数 / 阈值 / 状态(红黄绿 tag)/ 趋势(mini echarts 折线,数据来自 `getAlertDataList`);趋势列替换原 CSS 假图。
- 刷新:参考 AlertOverview —— `setInterval` 读后端(秒级体感读缓存)+ `visibilitychange` 回前台即刷 + 倒计时;handler 提为具名函数,`onBeforeUnmount` 正确 `removeEventListener` + `chart.dispose()`(避免 SecondChart/TrendDashboard 的泄漏 bug)。
- 下钻入口(P1 预留):表格行点击 → `router.push({path:'/alert-dashboard', query:{configId}})`(复用已有 AlertDashboard);P1 再做站内服务日志下钻。

### 任务 3(可选):monitor.js 聚合封装

**文件:** 新增 `src/api/monitor.js`

**实现要点:**
- 封装 `getDashboardOverview()` 之类(若后端后续提供聚合接口)。P0 不强需——直接用 alert.js / errorAnalysis.js 即可,此文件预留。

---

## 注意

- echarts 沿用全量 import;resize handler 具名化避免泄漏。
- 401 不跳登录:P0 不处理鉴权跳转。
- `timeSlots` 死代码清理;保留 `handleCommand` 顶部 dropdown 切换。
