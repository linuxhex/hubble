# 测试报告 — 钉钉告警链接地址修正

## 概要
- 测试时间：2026-10-15 19:50 ~ 20:22
- 项目类型：Web
- 测试环境：http://172.16.149.95:18081（deploy-95.sh 部署后的生产包）
- 驱动引擎：ego-browser（web-run.cjs Plan+Executor 模式）
- 场景总数：11（UI 9 + API 2）
- 通过：11
- 失败：0
- 通过率：100%

## 测试背景

本次修改将钉钉群告警消息中的"查看详情"链接从 localhost 指向部署地址 `http://172.16.149.95:18081`，并将 hash 路由格式（`/#/xxx`）修正为 history 路由格式（`/xxx`）。本测试验证部署后各告警目标页面直连可正常打开渲染。

## 详细结果

### API 测试（2/2 通过）

| # | 场景 | 优先级 | 结果 | 证据 |
|---|------|--------|------|------|
| 1 | 无 token 访问 /api/alert-config → 401 | P0 | ✓ | curl HTTP 401 |
| 2 | 有效 JWT 访问 /api/alert-config/query → code 200，8 条告警配置 | P0 | ✓ | curl 响应体 |

### UI 测试（9/9 通过）

| # | 场景 | 优先级 | 步骤 | 结果 | 截图 |
|---|------|--------|------|------|------|
| 1 | 未登录访问 /abnormal → 跳钉钉 OAuth | P0 | navigate → aiAssert | ✓ redirect_uri 指向部署地址，授权页正常渲染 | 01-未登录钉钉OAuth跳转.png |
| 2 | /gateway 网关大盘直连 | P0 | 根容器+API+aiAssert | ✓ 统计卡片+趋势图 | 02-网关大盘.png |
| 3 | /abnormal 异常大盘直连 | P0 | 根容器+API+aiAssert | ✓ 分钟时间线含红盘高亮 | 03-异常大盘.png |
| 4 | /middleware 中间件直连 | P0 | 根容器+API+aiAssert | ✓ Redis 实例列表含版本列 | 04-中间件.png |
| 5 | /service-load 服务负载直连 | P0 | 根容器+API+aiAssert | ✓ 扩容决策统计+四列水位 | 05-服务负载.png |
| 6 | /degradation-ranking 直连 | P0 | 根容器+aiAssert | ✓ 劣化排行表 | 06-接口劣化.png |
| 7 | /degradation-ranking?api=… 带参数 | P0 | 根容器+aiAssert | ✓ 不白屏无崩溃 | 07-接口劣化带参数.png |
| 8 | /traffic-surge 流量暴涨直连 | P0 | 根容器+aiAssert | ✓ 统计卡片+排名表 | 08-流量暴涨.png |
| 9 | /alert-config 告警配置直连 | P0 | 根容器+API+aiAssert | ✓ 四 tab+规则列表 | 09-告警配置.png |

### 关键佐证（服务器侧）

- 服务器进程 `/proc/<pid>/environ` 无 `MONITOR_DASHBOARD_URL` 覆盖 → application.yml 默认值 `http://172.16.149.95:18081` 生效
- 未登录跳钉钉 OAuth 的 redirect_uri 参数 = `http://172.16.149.95:18081/login` → 钉钉侧回调配置已指向部署地址
- `/abnormal`、`/alert-config` 等 history 路由直连 HTTP 200（SPA fallback 生效）

## 结果质询（自问自答）

- **自问**：全部通过是否因测试太浅？隐藏 bug 最可能藏在哪？
  **自答**：机器人测试发送（testSend）的链接是本次修改点之一，但触发会真实发钉钉消息打扰群成员，自动化不触发；已通过代码审查确认其注入 MonitorProperties 且编译通过，与页面场景共用同一 dashboardUrl 配置源。记录为未覆盖项。
- **自问**：守卫重定向只测了 /abnormal，其他页面未登录行为一致吗？
  **自答**：路由守卫是全局 `router.beforeEach` 单一实现，/abnormal 场景代表全部私有路径。确认可靠。

## 测试方法说明

- 钉钉扫码 OAuth 无法自动化，鉴权采用测试 JWT（HS256，.env 密钥签发，2 小时有效，userId=1/username=lianzi）注入 localStorage，与 self-test-20260928 自测方案一致
- 注入方式：CDP `Page.addScriptToEvaluateOnNewDocument` 在文档加载早期写入（规避守卫先于注入重定向到钉钉域的竞速问题）

## 未覆盖项
1. 机器人「测试发送」真实链路（会向钉钉群发真实消息，需人工在告警配置页点击验证）
2. 真实钉钉客户端内点链接的免登回跳（需人工在钉钉群内点击验证）

## 结论

部署后所有钉钉告警链接目标页面（8 个路由）history 直连渲染正常，鉴权链路（401/200、OAuth 回跳）正确，本次告警链接地址修正验证通过。
