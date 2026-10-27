# 测试报告

## 概要
- 测试时间：2026-09-15 13:30
- 项目类型：Web
- 测试环境：http://localhost:82 (前端) + http://localhost:18081 (后端)
- 驱动引擎：ego-browser
- 场景总数：6
- 通过：6
- 失败：0
- 通过率：100%

## 详细结果

### 场景 1：网关概览 /gateway ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 打开 /gateway | ✓ 页面加载成功 | |
| 2 | 验证数据展示 | ✓ 总请求量 29,748,224 / 平均RT 194ms / 错误率 0.05% / QPS 641.93 | screenshots/ego-browser-shot-31892-1.png |
| 3 | 验证图表 | ✓ 请求趋势图正常渲染（INFO/WARN/ERROR 三线） | |

### 场景 2：网关日志 /gateway/logs ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 打开 /gateway/logs | ✓ 页面加载成功 | |
| 2 | 验证搜索功能 | ✓ 搜索框和服务选择器存在 | screenshots/ego-browser-shot-31892-2.png |
| 3 | 验证日志展示 | ✓ 日志表格渲染正常 | |

### 场景 3：链路追踪 /gateway/trace ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 打开 /gateway/trace | ✓ 页面加载成功 | |
| 2 | 验证链路元素 | ✓ 链路追踪相关组件存在 | screenshots/ego-browser-shot-31892-3.png |

### 场景 4：用户行为 /user-behavior ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 打开 /user-behavior | ✓ 页面加载成功 | |
| 2 | 验证用户行为元素 | ✓ 用户行为相关组件存在 | screenshots/ego-browser-shot-31892-4.png |

### 场景 5：业务监控 /biz-analysis ✓
| 步骤 | 操作 | 结果 | 截图 | 备注 |
|------|------|------|------|------|
| 1 | 打开 /biz-analysis | ✓ 页面加载成功 | | |
| 2 | 验证业务监控元素 | ✓ 概览卡片、图表、对比图存在 | screenshots/ego-browser-shot-31892-5.png | |
| 3 | 验证数据 | ⚠ 概览卡片全为 0 | | Doris query-server 不可达 |

### 场景 6：服务负载 /service-load ✓
| 步骤 | 操作 | 结果 | 截图 |
|------|------|------|------|
| 1 | 打开 /service-load | ✓ 页面加载成功 | |
| 2 | 验证服务负载元素 | ✓ 服务负载相关组件存在 | screenshots/ego-browser-shot-31892-6.png |

## 后端 API 验证

| API | 状态 | 数据 | 备注 |
|-----|------|------|------|
| GET /api/gateway/overview | ✓ 200 | 有真实数据 | SLS/ARMS 正常 |
| GET /api/biz-analysis/overview | ✓ 200 | 全 0 | Doris query-server 502 |
| GET /api/biz-analysis/monthly-trend | ✓ 200 | 空数组 | Doris 不可达 |
| GET /api/biz-analysis/daily | ✓ 200 | 空数组 | Doris 不可达 |
| GET /api/alert-threshold/list | ✓ 200 | 21 条 | 正常 |
| GET /api/service-load/list | ✓ 200 | 空 | Grafana 不可达 |

## 发现的问题

### 问题 1：业务监控数据全为 0（已知环境问题）
- **现象**：业务监控页面所有概览卡片显示 0，图表为空
- **根因**：`application.yml` 中 `query-server-url: ${MCP_BASE_URL:}` 环境变量 `MCP_BASE_URL` 未设置，导致 URL 为空
- **已修复**：改为 `${MCP_BASE_URL:http://query-server-internal:8080}`
- **当前状态**：`query-server-internal` 是 K8s 内部域名，本地无法解析，需设置 `MCP_BASE_URL` 指向可达地址

### 问题 2：Grafana URL 为空（同类问题）
- **现象**：中间件监控、服务负载页面数据为空
- **根因**：`GRAFANA_URL` 环境变量未设置，导致 `URI with undefined scheme`
- **建议**：设置 `GRAFANA_URL` 环境变量

### 问题 3：本地测试需钉钉 OAuth（环境问题）
- **现象**：页面导航时跳转到钉钉登录页
- **根因**：axios 401 拦截器清除 token 并跳转 `/login`，Login 组件跳钉钉 OAuth
- **影响**：本地无法通过浏览器完整测试需要认证的页面
- **建议**：开发环境增加 mock 登录或跳过认证

## 修复建议
1. 本地开发设置环境变量：`MCP_BASE_URL`、`GRAFANA_URL`、`JWT_PUBLIC_KEY`
2. 或添加开发环境 mock 登录机制，避免依赖钉钉 OAuth
