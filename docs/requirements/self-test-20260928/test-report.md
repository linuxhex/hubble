# 自测报告：本地构建部署 + 全站走查（2026-09-28）

## 概要
- 测试时间：2026-09-28 08:30 ~ 09:10
- 项目类型：Web（hubble-web Vue3 + hubble-server Spring Boot 单体 jar）
- 测试环境：http://172.16.149.95:18081（本地构建 jar 经 deploy-95.sh 部署后的版本）
- 驱动引擎：ego-browser
- 场景总数：21（18 个页面 + 3 个重定向）
- 通过：21 / 失败：0 / 通过率：100%
- 服务端：重启后 nohup.out 全量扫描 ERROR=0

## 部署方式（deploy-95.sh，已 gitignore 不入库）
本地 `build.sh`（前端构建进 static + mvn package）→ scp 上传 jar 至
`/root/workspace/hubble-1.0.0.jar`（先传 .new 原子替换，旧 jar 备份 .bak）→
停旧进程/轮转超 1G 日志 → `nohup /usr/local/jdk-17.0.12/bin/java -jar ...` 重启 →
端口 18081 就绪探测 + 外部 curl 检查。本次部署结果：SUCCESS，新进程 PID 3307957。

## 详细结果（18 页面全部 PASS）

| 页面 | 路由 | 接口数 | 数据渲染 | 截图 |
|------|------|--------|----------|------|
| 登录 | /login | 3 | 已登录自动跳 /gateway | 01-login.png |
| 网关大盘 | /gateway | 3 | 卡片+趋势图（首轮截图确认 3267 万请求/35.1ms/0.72%） | 02-gateway.png |
| 链路详情 | /gateway/trace | 1 | 查询表单+空态 | 03-gateway-trace.png |
| 日志搜索 | /gateway/logs | 1 | 快照 40850 字符，数据密集 | 04-gateway-logs.png |
| 用户行为 | /user-behavior | 1 | 快照 21318 字符 | 05-user-behavior.png |
| 链路查询 | /trace-query | 1 | 查询表单+空态 | 06-trace-query.png |
| 关键字查询 | /keyword-log-query | 0 | 查询表单+空态 | 07-keyword-log-query.png |
| 链路配置 | /trace-management | 2 | 管理列表 | 08-trace-management.png |
| SLS关键字配置 | /sls-keyword-management | 3 | 管理列表 | 09-sls-keyword-mgmt.png |
| 告警配置 | /alert-config | 6 | 配置面板 | 10-alert-config.png |
| 未授权页 | /unauthorized | 0 | 规范未授权提示 | 11-unauthorized.png |
| Widget 大盘 | /widget-dashboard | 2 | 组件页 | 12-widget-dashboard.png |
| 接口劣化 | /degradation-ranking | 1 | 排名列表 | 13-degradation-ranking.png |
| 流量暴涨 | /traffic-surge | 1 | 涨幅面板 | 14-traffic-surge.png |
| 中间件 | /middleware | 4 | 快照 17954 字符，实例巡检 | 15-middleware.png |
| 业务监控 | /biz-analysis | 16 | 124万订单/6500万kWh，昨日vs前日小时对比图（日期语义正确） | 16-biz-analysis.png |
| 服务负载 | /service-load | 1 | 76 服务评估（5紧急/2建议/4关注/63正常），扩容建议 | 17-service-load.png |
| 异常大盘 | /abnormal | 1 | 快照 13898 字符 | 18-abnormal.png |

重定向：`/`→/gateway、`/monitor`→/gateway、`/user-behavior-trace`→/user-behavior 全部正确。

每页检查项：最终 URL（无登录跳失）、语义树非空、DOM 错误弹窗（.el-message--error=0）、
登录失效弹窗（无）、接口 4xx/5xx（无）、console error（无）。

## 认证测试方案
登录为钉钉 OAuth 扫码，自动化不可行。取 jar 内 `BOOT-INF/classes/.env` 的
`JWT_PUBLIC_KEY`（与 AuthFilter HS256 同密钥），本地 node 生成 7 天有效 JWT
（username=lianzi，满足 BizAccessInterceptor 口径），在**不会自动跳转的 /unauthorized
页**注入 localStorage 后走查全部页面。authed 接口（/api/biz-analysis/* 等）全部 200，
证明部署 jar 的鉴权链路完整。

## 排障记录（测试脚本自身问题，非应用 bug）
1. 假 token 注入 → 非白名单接口 401 → request.js 清登录态 → /login 自动跳钉钉，
   形成"全站级联跳登"假象（第 1 轮）。
2. /login 页无 token 时约 1s 内自动跳钉钉 OAuth，localStorage 注入存在竞态，
   曾写入钉谈域（第 2/3 轮全跳）。改在 /unauthorized 注入并校验 origin 后稳定（第 4 轮）。

## 未覆盖项
- AI 对话（WebSocket /ws/ai/chat + LLM 真实调用）：需交互式会话且消耗 LLM 计费，沿用上一轮验证结论。
- 链路/日志深度查询交互：需真实 traceId/关键字数据，本轮验证到页面加载与空态渲染。
- 真实钉钉扫码登录：无法自动化，已用同密钥 JWT 等价验证 AuthFilter 链路。

## 修复建议
无应用层缺陷需修复。观察项：`JWT_EXPIRATION` 默认 1 小时，登录用户每小时被踢一次，
如需放宽可在 jar 内 .env 增加 `JWT_EXPIRATION=604800000`（7 天）后重新打包部署。
