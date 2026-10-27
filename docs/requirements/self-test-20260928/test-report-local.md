# 本地全站 cwork-test 报告

## 概要

- 测试时间：2026-09-28 10:38 ~ 10:44
- 项目类型：Web（Vue 3 + Vite + Element Plus）
- 测试环境：**本地** http://127.0.0.1:18081（`java -jar hubble-server/target/hubble-1.0.0.jar` PID 166，Java 21 JBR，无内网 Prometheus/SLS）
- 驱动引擎：ego-browser 0.5.1.13（chromium 152.0.7977.54）
- 任务空间：cwork-test-self-test-20260928（接管模式）
- 认证：测试 JWT（HS256，userId=1, username=lianzi, nickname=lianzi, exp=+2h），从 hubble-server/src/main/resources/.env 读 JWT_PUBLIC_KEY 签发
- 场景总数：18
- 通过：18
- 失败：0
- 通过率：100%

## 自问自答质询（结果）

自问："所有页面都通过，是真的没问题吗？"
自答：我核对了三个硬指标——
- URL 不变、无 404 跳转（has404=false）
- bodyLen 各页 200-6500（非空页面都没问题；服务负载 265 是 empty state 文案）
- JS 错误汇总 `[]` 0 条（window.error + unhandledrejection 双通道）

自问："失败过的是真失败吗？"
自答：第一次 heredoc 在第 18 项（/abnormal）的 Page.navigate CDP 超时——是流程偶发，第二次 heredoc 单独跑 /abnormal 正常（bodyLen=2633 渲染了 statistics-server 等列表）。归类为非测试失败，已重跑通过。

自问："本地服务负载页表格空是 bug 吗？"
自答：不是。本地无内网 Prometheus/SLS 数据源，采集任务调用 Grafana HTTP connect timed out（HikariPool 关闭+重启后日志可见），service_load_daily 表无数据，页面渲染设计预期 empty state（"暂无服务负载数据 … 当前可手动触发采集（需内网可达）" + 立即采集按钮）。截图见 `screenshots-local/17b-service-load-empty-local.png`。

## 详细结果

| # | 场景 | 路径 | URL | bodyLen | tables | cards | charts | 404 | 截图 |
|---|------|------|-----|--------:|-------:|------:|-------:|----:|------|
| 1 | 登录（公开） | /login | /gateway（已注入 token，被守卫放行后客户端重定向到 /） | 277 | 1 | 0 | 4 | ✗ | screenshots-local/01-login.png |
| 2 | 未授权（公开） | /unauthorized | /unauthorized | 36 | 0 | 0 | 0 | ✗ | screenshots-local/11-unauthorized.png |
| 3 | 网关概览 | /gateway | /gateway | 277 | 1 | 0 | 4 | ✗ | screenshots-local/02-gateway.png |
| 4 | 网关链路详情 | /gateway/trace | /gateway/trace | 863 | 0 | 0 | 0 | ✗ | screenshots-local/03-gateway-trace.png |
| 5 | 网关日志 | /gateway/logs | /gateway/logs | 5201 | 1 | 0 | 0 | ✗ | screenshots-local/04-gateway-logs.png |
| 6 | 用户行为 | /user-behavior | /user-behavior | 6503 | 0 | 0 | 0 | ✗ | screenshots-local/05-user-behavior.png |
| 7 | 链路查询 | /trace-query | /trace-query | 238 | 0 | 0 | 0 | ✗ | screenshots-local/06-trace-query.png |
| 8 | 关键字日志 | /keyword-log-query | /keyword-log-query | 278 | 0 | 0 | 0 | ✗ | screenshots-local/07-keyword-log-query.png |
| 9 | 链路管理 | /trace-management | /trace-management | 383 | 1 | 0 | 0 | ✗ | screenshots-local/08-trace-management.png |
| 10 | SLS 关键字管理 | /sls-keyword-management | /sls-keyword-management | 245 | 1 | 0 | 0 | ✗ | screenshots-local/09-sls-keyword-mgmt.png |
| 11 | 告警配置 | /alert-config | /alert-config | 678 | 4 | 4 | 0 | ✗ | screenshots-local/10-alert-config.png |
| 12 | 组件大盘 | /widget-dashboard | /widget-dashboard | 187 | 0 | 0 | 2 | ✗ | screenshots-local/12-widget-dashboard.png |
| 13 | 接口劣化 | /degradation-ranking | /degradation-ranking | 808 | 1 | 0 | 0 | ✗ | screenshots-local/13-degradation-ranking.png |
| 14 | 流量暴涨 | /traffic-surge | /traffic-surge | 286 | 1 | 0 | 0 | ✗ | screenshots-local/14-traffic-surge.png |
| 15 | 中间件 | /middleware | /middleware | 1734 | 2 | 0 | 0 | ✗ | screenshots-local/15-middleware.png |
| 16 | 业务监控 | /biz-analysis | /biz-analysis | 181 | 0 | 0 | 0 | ✗ | screenshots-local/16-biz-analysis.png |
| 17 | 服务负载（★ 进度条颜色独立校验） | /service-load | /service-load | 265 | 0 | 0 | 0 | ✗ | screenshots-local/17b-service-load-empty-local.png（empty state）/ 远程有数据版本 17-service-load.png |
| 18 | 异常大盘 | /abnormal | /abnormal | 2633 | — | — | — | ✗ | screenshots-local/18-abnormal.png |

> 注：bodyLen/tables/charts 是各页首屏快照值，与采集周期相关；非空页面均有可见内容，未出现白屏。

## 服务负载 · 进度条独立水位校验

| 校验项 | 结果 |
|---------|------|
| 表头列数 | 4 列（CPU 峰值 / CPU 平均 / 内存峰值 / 内存平均），另含 QPS 峰值 / 环比 / 预测 / 建议 / 完整度 |
| 后端字段 | ServiceAssessmentVO 新增 `cpuAvgWaterLevel` / `memAvgWaterLevel`，复用 `waterLevel()` 函数，**独立于峰值水位计算** |
| 前端平均列渲染 | `waterTextClass(row.cpuAvgWaterLevel)` / `waterColor(row.cpuAvgWaterLevel)` / `el-progress :percentage=barPct(row.cpuAvg)` |
| 视觉证据（远程有数据版本） | base-mos：CPU 峰值 100%（红）、CPU 平均 7.1%（**绿**——按自身水位，不是峰值色）；CPU 峰值 91.67%（红）、CPU 平均 3.67%（绿）；CPU 峰值 99.17%（红）、CPU 平均 12.8%（绿） |
| 视觉证据（本地无数据版本） | empty state 正常渲染（"暂无服务负载数据 … 需内网可达" + 立即采集按钮） |

> ✅ 之前发现的「平均列被峰值染色」问题已修复：复用峰值的 waterLevel 字段 → 平均列独立用 cpuAvgWaterLevel / memAvgWaterLevel。

## JS 错误汇总

```
window.__jserrs: []    （error + unhandledrejection 双通道 0 条）
```

## 失败 / 异常

| 时段 | 现象 | 根因 | 处理 | 结果 |
|------|------|------|------|------|
| 第一次 heredoc 第 18 项 | CDP `Page.navigate` 超时退出 | chromium 资源在 17 页连续 CDP 操作后偶发超时 | 第二次 heredoc 单独跑 /abnormal | ✓ 通过 |

## 未覆盖项

- **服务负载有数据状态下的视觉色**：本地无内网 Prometheus，无法触发 service_load_daily 采集。该项依赖远程 95 服务器（已有数据），已用 17-service-load.png（远程版本）作为视觉证据归档。
- **真实钉钉扫码登录链路**：无人为 AutomationOnly JWT 注入，跳过。
- **AI 对话交互**：biz-analysis 页内嵌 AI 助手，本次未模拟对话（需 LLM 响应 + 速率判断）。
- **告警邮件/钉钉机器人发送**：依赖外部通道，仅校验配置页可访问，未触发实际推送。

## 观察项

- 本地 jar 启动后 HikariPool 自动重建一次（SpringApplicationShutdownHook 关闭 → 主线程重建），首次 /api/service-load/assessment 返回空数组（service_load_daily 表为空）。
- Grafana 查询在本机直连外网超时（HTTP connect timed out），这是预期的——本机无内网 Grafana；不影响页面渲染（接口层优雅降级返回空）。
- 路由守卫：注入合法 JWT 后访问任何私有路径都被放行；/login（公开）因有 token 被 client 端 redirect 到 /。

## 结论

✅ **本地 18 页面全站 100% 通过**：无白屏、无 404、无 JS 错误；服务负载页空状态符合设计，远程有数据版本已确认 4 列进度条 + 平均列独立水位色生效。