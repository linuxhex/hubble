# 系统完善补充方案（全面体检版）

## Context
对 hubble 监控大盘做全面体检（前端逐页面 + 后端接口 + 交互细节），发现大量展示不完整、功能缺失、体验问题。本方案补充修复，与 AI 对话 / 中间件扩展 / 经营分析三块方案合并执行。

---

## 一、孤儿页面：12 个页面有路由无菜单入口（P0 最严重）

| 路由 | 组件 | 功能 |
|------|------|------|
| `/alert-config` | AlertConfigManagement | 告警配置管理 |
| `/alert-dashboard` | AlertDashboard | 告警大盘 |
| `/alert-overview` | AlertOverview | 告警概览 |
| `/trace-query` | TraceQuery | 业务链路查询 |
| `/trace-management` | TraceManagement | 链路配置 |
| `/keyword-log-query` | KeywordLogQuery | 关键字日志查询 |
| `/sls-keyword-management` | SlsKeywordManagement | SLS关键字管理 |
| `/user-behavior-trace` | UserBehaviorTraceQuery | 用户行为轨迹 |
| `/widget-dashboard` | WidgetDashboard | 组件大盘 |
| `/trend-dashboard` | TrendDashboard | 趋势大盘 |
| `/second-chart` | SecondChart | 秒级监控图 |

### 修复：`src/App.vue` 菜单分组重构
```
监控大盘
  ├─ 网关大盘（/gateway）
  ├─ 异常监控（/abnormal）
  ├─ 接口劣化（/degradation-ranking）
  ├─ 流量暴涨（/traffic-surge）
  ├─ 中间件监控（/middleware）
  ├─ 趋势大盘（/trend-dashboard）
  ├─ 组件大盘（/widget-dashboard）
  └─ 秒级图表（/second-chart）
链路追踪
  ├─ 链路查询（/trace-query）
  ├─ 链路管理（/trace-management）
  ├─ 网关链路（/gateway/trace）
  └─ 用户行为（/user-behavior）
日志搜索
  ├─ 网关日志（/gateway/logs）
  ├─ 关键字查询（/keyword-log-query）
  ├─ 用户行为轨迹（/user-behavior-trace）
  └─ SLS关键字管理（/sls-keyword-management）
告警管理
  ├─ 告警大盘（/alert-dashboard）
  ├─ 告警概览（/alert-overview）
  └─ 告警配置（/alert-config）
经营分析（仅 lianzi）
  └─ 经营分析（/biz-analysis）
```

---

## 二、数据造假 / 语义错误（P0 必须修）

| 页面 | 问题 | 修复 |
|------|------|------|
| `TrendDashboard` | P95 = QPS×1.5、吞吐量 = QPS/100，**纯伪造数据** | 接入真实 P95/吞吐量接口，或明确标注"估算" |
| `SecondChart` | 吞吐量 = totalRequests/1024/1024，**把请求数除以1MB当吞吐量** | 修正计算或移除该指标 |
| `TrendDashboard` | "吞吐量"卡片显示 totalRequests（总请求数），语义错误 | 改标签为"总请求数"或接真实吞吐量 |
| `GatewayTrace` | 总耗时 = 所有节点耗时求和，**重复计算**（父含子） | 改为根节点耗时 |
| `GatewayDashboard` | 趋势颜色：错误率上升显示绿色（好事），语义反了 | 按指标语义区分正反向（错误率/RT 上升=红色） |
| `GatewayLogs` | 详情弹窗 requestBody/responseBody 是 `JSON.stringify` 伪造的 | 标注"字段汇总"或接真实请求响应数据 |
| `SlsKeywordManagement` | 提示文本"Milvus 每10秒自动刷新"——从别处复制来的错误文案 | 改为正确文案 |

---

## 三、空指针崩溃风险（P0）

遍布所有表格：`row.cpuUsage.toFixed(1)`、`row.currentAvgTime.toFixed(1)` 等，后端返回 null 时前端直接崩。

### 修复：全局格式化工具 `src/utils/format.js`
```js
export const safeToFixed = (val, n = 1) => (val != null && !isNaN(val)) ? Number(val).toFixed(n) : '--'
export const safeRound = (val) => (val != null && !isNaN(val)) ? Math.round(val) : '--'
export const safePercent = (val, n = 1) => safeToFixed(val, n) + '%'
```
所有 `.toFixed()` / `Math.round()` 调用替换为 safe 版本。涉及：`MiddlewareDashboard`、`ApiDegradation`、`TrafficSurgeDashboard`、`AlertDashboard`、`BizAnalysisDashboard`、`GatewayDashboard`。

---

## 四、内存泄漏（P0）

| 页面 | 泄漏 | 修复 |
|------|------|------|
| `GatewayDashboard` | resize 用匿名箭头，removeEventListener 无效 | 提取具名函数 |
| `TrendDashboard` | 同上 | 同上 |
| `AlertOverview` | wheel 事件每次 initChart 重复添加，从不移除 | init 前 remove |
| `AlertDashboard` | wheel 事件每次 updateChart 重复添加 | 同上 |
| `ErrorCountTrendChart` / `ErrorChangeRateChart` | wheel 泄漏 | 同上 |

### 修复模式
```js
// ❌ 错误
window.addEventListener('resize', () => chart?.resize())
onBeforeUnmount(() => window.removeEventListener('resize', () => chart?.resize())) // 无效

// ✓ 正确
const handleResize = () => chart?.resize()
window.addEventListener('resize', handleResize)
onBeforeUnmount(() => window.removeEventListener('resize', handleResize))
```

---

## 五、loading / 空状态 / 错误提示缺失（P0）

约 40% 页面无 loading，60% 页面静默吞错（`.catch(() => null)`），用户失败时无感知。

| 页面 | 修复 |
|------|------|
| `GatewayDashboard` | 加 `v-loading` + 错误 ElMessage + 图表 el-empty |
| `TrendDashboard` | 加 loading + 60s 刷新 + el-empty + 错误提示 |
| `WidgetDashboard` | 加 loading + 刷新 + 错误提示 |
| `MiddlewareDashboard` | 加自动刷新 + 子表格独立 loading |
| `BizAnalysisDashboard` | 加自动刷新 + 各区块独立 loading + 错误提示 |
| `ApiDegradation` / `TrafficSurge` | 加错误提示 |
| 所有 echarts 图表 | 数据为空时 `v-if="hasData"` + `<el-empty v-else />` |

---

## 六、导出 CSV（P1）

运维汇报刚需。抽公共工具 `src/utils/export-csv.js`：
```js
export function exportCSV(filename, rows, columns) {
  const header = columns.map(c => c.label).join(',')
  const body = rows.map(r => columns.map(c => `"${r[c.prop] ?? ''}"`).join(',')).join('\n')
  const blob = new Blob(['﻿' + header + '\n' + body], { type: 'text/csv;charset=utf-8' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = filename
  link.click()
}
```
加到：`ApiDegradation`、`TrafficSurgeDashboard`、`MiddlewareDashboard`、`AlertOverview`、`BizAnalysisDashboard`、`AlertDashboard`。

---

## 七、交互体验（P1）

### 7.1 暗色主题
`src/App.vue` 顶部加切换按钮，Element Plus `dark` class + CSS 变量，localStorage 记忆。

### 7.2 全屏切换
`document.fullscreenElement` API，全屏时隐藏侧边栏 + 顶栏。

### 7.3 移动端响应式
`src/App.vue` 侧边栏 `< 768px` 改 `el-drawer` 抽屉模式；表格加 `overflow-x: auto`；`AbnormalDashboard` 网格改 `auto-fill`。

### 7.4 分页
数据 > 20 行的表格加 `el-pagination`：`MiddlewareDashboard`、`AlertDashboard`、`BizAnalysisDashboard`、`UserBehavior`、`GatewayLogs`。

### 7.5 搜索防抖
所有搜索输入加 `lodash.debounce` 或手写 300ms 防抖：`AlertConfigManagement`、`SlsKeywordManagement`、`TraceManagement`。

### 7.6 输入校验
手机号格式、Webhook URL 格式、时间范围（开始 < 结束）：`AlertConfigManagement`、`UserBehavior`、`GatewayLogs`。

---

## 八、后端健壮性（P1）

### 8.1 H2 → MySQL（生产）
`application.yml` 用 H2 内存库，重启丢数据。新增 `application-prod.yml` 用 MySQL，`spring.profiles.active=dev` 区分。

### 8.2 JWT 平滑刷新
JWT 1h 过期直接跳登录。`src/utils/request.js` 检测 401 时若用户最近 5min 有操作，调 `/auth/refresh` 续期。后端 `AuthController` 加 `/auth/refresh` 接口。

### 8.3 AuthFilter 白名单
`/gateway/` `/middleware/` 全放行。加 `auth.strict-mode` 配置，严格模式下需 JWT。

### 8.4 request.js 健壮性
- 加重试（网络错误重试 1 次）
- `res.code` 判断改为兼容无 code 字段的响应
- 401 用 router.replace 而非 window.location.href（保留 SPA 状态）

---

## 九、死代码清理（P2）

| 文件 | 问题 |
|------|------|
| `MonitorDashboard.vue` | 被 import 但无路由（`/monitor` redirect 到 `/gateway`），完全不可达 |
| `ServiceDrillDown.vue` | 未被任何页面引用 |
| `TraceManagement` | `route.params.id` 死代码（路由无 :id 参数） |
| `AlertDashboard` | 残留 `console.log` |

### 修复
- `MonitorDashboard` / `ServiceDrillDown`：移除 import + 文件
- `TraceManagement`：移除 `route.params.id` 逻辑
- `AlertDashboard`：移除 console.log

---

## 十、视觉一致性（P2）

| 维度 | 现状 | 统一为 |
|------|------|--------|
| 页面 padding | 16/10/0px | 16px |
| 页面背景 | #f5f7fa / white | #f5f7fa |
| h2 字号 | 16/20px | 20px |
| 卡片圆角 | 4/8px | 8px |
| 蓝色色值 | #1890ff / #409eff | #409eff（Element Plus） |
| loading | v-loading / 自定义 / 无 | v-loading |
| 空状态 | el-empty / 自定义 / 无 | el-empty |
| 表格 size | small / 默认 | small |

抽 `src/styles/variables.css` 统一 CSS 变量，各页面引用。

---

## 十一、WidgetDashboard 功能补全（P2）

| 问题 | 修复 |
|------|------|
| 编辑功能"开发中" | 实现编辑（改标题/指标/时间范围） |
| 声明 5 种控件只支持 2 种 | 支持 bar/pie/table |
| 重置布局变 1 个控件（bug） | 重置为默认 4 个 |
| 无持久化 | localStorage 存布局 |

---

## 十二、文件清单

### 前端新增
- `src/utils/export-csv.js` — CSV 导出工具
- `src/utils/format.js` — 安全格式化（safeToFixed 等）
- `src/styles/dark.css` — 暗色主题
- `src/styles/variables.css` — 统一 CSS 变量

### 前端修改
- `src/App.vue` — 菜单分组 + 主题切换 + 全屏 + 移动端侧边栏
- `src/router/index.js` — 路由守卫加 lianzi 校验
- `src/utils/request.js` — 重试 + 401 平滑刷新 + code 兼容
- `src/components/GatewayDashboard.vue` — loading + empty + 趋势颜色 + resize 修复
- `src/components/TrendDashboard.vue` — 真实数据 + loading + 刷新 + resize 修复
- `src/components/SecondChart.vue` — 吞吐量修正 + resize 修复
- `src/components/GatewayTrace.vue` — 总耗时修正
- `src/components/GatewayLogs.vue` — 分页 + keyword 输入 + 详情标注
- `src/components/WidgetDashboard.vue` — 编辑 + 重置修复 + 持久化
- `src/components/ApiDegradation.vue` — null 防御 + 导出 + 倒计时修复
- `src/components/TrafficSurgeDashboard.vue` — null 防御 + 导出
- `src/components/MiddlewareDashboard.vue` — null 防御 + 导出 + 自动刷新 + 分页
- `src/components/AlertDashboard.vue` — 移除 console.log + wheel 修复 + 分页
- `src/components/AlertOverview.vue` — wheel 修复 + 空状态
- `src/components/AlertConfigManagement.vue` — 校验 + 空状态
- `src/components/BizAnalysisDashboard.vue` — 自动刷新 + 导出 + 独立 loading
- `src/components/SlsKeywordManagement.vue` — 提示文案修正
- `src/components/AbnormalDashboard.vue` — 网格响应式 + 展开更多
- `src/components/UserBehavior.vue` — 分页 + responseStatus 类型修正
- 所有 echarts 组件 — wheel/resize 泄漏修复

### 后端修改
- `application-prod.yml`（新增）— MySQL + 严格认证
- `config/AuthFilter.java` — strict-mode
- `controller/AuthController.java` — `/auth/refresh`

### 死代码清理
- 移除 `MonitorDashboard.vue`、`ServiceDrillDown.vue`
- `TraceManagement.vue` 移除 `route.params.id`
- `AlertDashboard.vue` 移除 console.log

---

## 十三、优先级

| 级别 | 内容 | 理由 |
|------|------|------|
| **P0** | 孤儿页面补菜单 | 12 个页面用户看不到 |
| **P0** | 数据造假/语义错误修正 | 趋势大盘 P95 是假的，误导决策 |
| **P0** | 空指针防御 | 后端返回 null 时前端崩溃 |
| **P0** | 内存泄漏修复 | 长时间使用后页面卡顿 |
| **P0** | loading/empty/错误提示 | 40% 页面加载时一片白 |
| **P1** | 导出 CSV | 运维汇报刚需 |
| **P1** | 暗色主题 | 夜间值守 |
| **P1** | 后端 prod 配置 + JWT 刷新 | 生产环境数据持久化 |
| **P1** | request.js 健壮性 | 网络波动体验 |
| **P2** | 全屏/移动端/分页/防抖/校验 | 体验优化 |
| **P2** | 死代码清理 | 代码卫生 |
| **P2** | 视觉一致性 | 美观 |
| **P2** | WidgetDashboard 补全 | 功能完善 |

---

## 十四、验证
1. 所有菜单项可点击进入对应页面，无死链
2. 趋势大盘 P95/吞吐量显示真实数据
3. 后端返回 null 时表格显示 `--` 不崩溃
4. 长时间使用（1h+）页面不卡顿（无内存泄漏）
5. 每个页面有 loading → 数据/空状态 → 错误提示
6. 表格可导出 CSV
7. 暗色主题切换正常，刷新后保持
8. 全屏切换正常
9. 窄屏（< 768px）侧边栏变抽屉，表格不溢出
10. JWT 过期前活跃用户自动续期
11. H2 → MySQL 切换后重启数据不丢
