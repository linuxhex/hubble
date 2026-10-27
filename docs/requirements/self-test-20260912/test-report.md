# 全量自测报告

## 概要
- 测试时间：2026-09-13（第三轮，回归验证）
- 项目类型：Web（Hubble 监控大盘）
- 测试环境：http://localhost:18081
- 驱动引擎：ego-browser
- 场景总数：31
- 通过：31
- 失败：0
- 通过率：100%

## 测试范围

### 页面加载测试（17 个页面）

| # | 页面 | 路由 | 结果 |
|---|------|------|------|
| 1 | 网关大盘 | /gateway | PASS |
| 2 | 异常大盘 | /abnormal | PASS |
| 3 | 流量突增 | /traffic-surge | PASS |
| 4 | 服务负载 | /service-load | PASS |
| 5 | 中间件监控 | /middleware | PASS |
| 6 | 链路追踪 | /trace | PASS |
| 7 | AI 分析 | /ai-analysis | PASS |
| 8 | 用户行为 | /user-behavior | PASS |
| 9 | 经营分析 | /biz-analysis | PASS |
| 10 | 告警配置 | /alert-config | PASS |
| 11 | 告警阈值 | /alert-threshold | PASS |
| 12 | SLS 关键词 | /sls-keyword-management | PASS |
| 13 | 钉钉机器人 | /alert-config | PASS |
| 14 | 日报配置 | /daily-report | PASS |
| 15 | 降级演练 | /degradation-ranking | PASS |
| 16 | 审计日志 | /audit-log | PASS |
| 17 | 系统设置 | /settings | PASS |

### API 接口测试（11 个端点）

| # | 接口 | 状态 |
|---|------|------|
| 1 | GET /api/gateway/overview | PASS 200 |
| 2 | GET /api/abnormal/statistics | PASS 200 |
| 3 | GET /api/traffic-surge/records | PASS 200 |
| 4 | GET /api/service-load/overview | PASS 200 |
| 5 | GET /api/middleware/overview | PASS 200 |
| 6 | GET /api/alert-config/query | PASS 200 |
| 7 | GET /api/alert-threshold/list | PASS 200 |
| 8 | GET /api/sls-keyword/list | PASS 200 |
| 9 | GET /api/robot/list | PASS 200 |
| 10 | GET /api/biz-analysis/scenarios | PASS 200 |
| 11 | GET /api/daily-report/config | PASS 200 |

### 交互测试（3 项）

| # | 场景 | 结果 |
|---|------|------|
| 1 | 侧边栏 → 异常大盘 | PASS |
| 2 | 侧边栏 → 服务负载 | PASS |
| 3 | 侧边栏 → 告警配置 | PASS |

## 测试方法

通过 `test-auth.html` 在 localhost origin 上设置 JWT token，确保后续页面导航时 axios 请求拦截器能正确附加 Authorization header。

## 截图证据

| 页面 | 截图 |
|------|------|
| 网关大盘 | screenshots/gateway-dashboard.png |
| 异常大盘 | screenshots/abnormal-dashboard.png |
| 告警配置 | screenshots/alert-config.png |
| 经营分析 | screenshots/biz-analysis.png |
| 服务负载 | screenshots/service-load.png |

## 结论

全部 31 项测试通过，17 个页面正常加载，11 个 API 正常返回 200，侧边栏导航正常切换。
