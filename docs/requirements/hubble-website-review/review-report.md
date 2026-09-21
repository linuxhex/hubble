# Hubble 网站评审优化完整总结

> 需求键：hubble-website-review ｜ 周期：2026-09-21 单日完成 ｜ 状态：已全部提交
> 本文是本次评审-修复-优化-验证全流程的收敛文档。测试细节见同目录 [test-report.md](./test-report.md)。

## 一、背景与执行概览

对 Hubble 监控网站（前端 11 页 + 本地后端）进行了"以使用者身份实际操作"的全链路评审，按四阶段推进：

| 阶段 | 内容 | 提交 |
|------|------|------|
| 1. 系统评审 | 全站走查 + 数据准确性专项（伪造数据/口径错位/名实不符排查） | 48adb74 |
| 2. 体验优化 | 401 处理、空态引导、脱敏、白名单收紧等 8 项 | 93bf8b9 |
| 3. 使用者视角修复 | 真实操作动线（值班工程师的一天 J1~J11）发现 4 卡点并全量修复 | 34155fe |
| 4. 测试验证 | ego-browser 真实浏览器 5 轮走查，场景全通过 | test-report.md |

三个提交合计：后端 8 个文件、前端 16 个文件、文档 1 处，净变化 +1033 / -786。

## 二、系统现状快照

### 2.1 技术栈与部署形态
- **前端**：Vue 3 + Vite + Element Plus + ECharts；**history 路由**（createWebHistory，非 hash）；dev 端口 82（代理 18081）；构建产物同时部署在后端 `static/`
- **后端**：Spring Boot 3 + Java 21 + MyBatis-Plus + H2；端口 18081；本地以 `start.sh` 拉起
- **鉴权**：钉钉 OAuth 换 JWT；`AuthFilter` 只拦 `/api/` 路径并按白名单放行；前端 localStorage 存 token + 路由守卫（PUBLIC_PATHS 仅 /login、/unauthorized；/biz-analysis、/abnormal 额外限 nickname=lianzi）

### 2.2 页面清单（菜单 12 项 + 隐藏路由）
| 菜单项 | 路由 | 组件 |
|--------|------|------|
| 网关概览 | /gateway | GatewayDashboard |
| 异常大盘 | /abnormal（lianzi） | AbnormalDashboard |
| 接口劣化 | /degradation-ranking | ApiDegradation |
| 流量暴涨 | /traffic-surge | TrafficSurgeDashboard |
| 中间件 | /middleware | MiddlewareDashboard |
| 服务负载 | /service-load | ServiceLoadDashboard |
| 链路详情 | /gateway/trace | GatewayTrace |
| **链路配置（本轮新增）** | /trace-management | TraceManagement |
| 用户行为 | /user-behavior | UserBehavior |
| 日志搜索 | /gateway/logs | GatewayLogs |
| 告警配置 | /alert-config | AlertConfigManagement |
| 业务监控 | /biz-analysis（lianzi） | BizAnalysisDashboard |

隐藏路由：/trace-query（业务链路查询，从空态/引导可达）、/keyword-log-query（日志检索）、/sls-keyword-management、/widget-dashboard、/user-behavior-trace（已删除组件，redirect → /user-behavior）。

### 2.3 免登白名单现状（AuthFilter EXCLUDE_PATHS，两轮收紧后）
- 健康与登录：/system/health、/auth/verify、/auth/dingtalk/login、/swagger-ui、/v3/api-docs
- 监控大盘：/alert-data/*（query/service-health/minute-timeline/service-drilldown/service-logs/trace-logs/sse）、/error-analysis/query、/alert-threshold/、/dingtalk-robot/
- 业务接口：/gateway/、/middleware/、/middleware-alert/、/service-load/、/ws/ai/chat
- **显式保护路径（白名单前缀内仍要求认证）**：/gateway/logs/query（SLS 日志内容读取）
- **已移除**：/traces/query（业务链路查询）、/sls-keywords/query（关键字模版配置）、/biz-analysis/（业务监控数据）——三者无 token 均 401

> 评审误判纠正：最初认为 /sls-keywords/query 是日志内容读取接口；实际调查确认其为 SLS 关键字模版配置查询（列表/应用名/标签），真正免登暴露日志内容的是被 `/gateway/` 前缀放行的 POST /gateway/logs/query，已通过显式保护路径堵住。

### 2.4 数据源映射
| 数据 | 来源 | 备注 |
|------|------|------|
| 网关采样（RT/错误率/QPS） | SLS 真实采样 | 页面标注「数据源: SLS」+ 估算项带角标 |
| 链路/慢查询 | ARMS trace（3 并发信号量 + 缓存 TTL） | Redis/MySQL 慢查询 Top10 |
| 服务负载/资源 | Prom 自动发现 + 启动补采 | 带数据新鲜度标注 |
| Redis 实例内存 | CloudMonitor | 非 Key 级，页面有口径说明 |
| 告警状态 | H2 持久化 + 内存恢复 | 重启不丢 |
| 业务监控（订单/DAU 等） | biz-analysis 服务 | 六卡 + 10 图表 |

## 三、优化全记录（按提交）

### 3.1 48adb74 第一批「数据准确性：诚实化 + 降噪」（18 文件，+567/-292）
| # | 问题 | 修复 | 验证 |
|---|------|------|------|
| 1 | 网关 RT 为伪造固定值 | 删除伪造逻辑，改 SLS 真实采样 | 页面 rtSample=39ms |
| 2 | 错误率口径错误 | 接口口径修正 | errorRate=0.86% |
| 3 | 告警状态重启丢失 | alert_monitor_state 表 + 内存恢复 | 建表日志 + 重启恢复确认 |
| 4 | P60 非真实分位 | 真实分位计算 | 接口复核 |
| 5 | 大盘空分钟伪造数据 | 空分钟诚实化为空数据点（status=NORMAL） | 异常大盘实测 |
| 6 | Redis tab 名实不符 | 更名「实例内存 Top10」+ CloudMonitor 口径说明 alert | 页面实测 tabRenamed=true |
| 7 | MAU 图表实为月均 DAU | 更名「月均 DAU 趋势」 | 页面标题实测 |
| 8 | 劣化/暴涨噪声刷屏 | <20% 变化率 / <50% 涨幅过滤 | 劣化剩 5 条、暴涨剩 3 条 |
| 9 | trace 全量缓存无过期 | 缓存 TTL | 代码级验证 |
| 10 | 数据来源不可见 | 前端「数据源: SLS」标签 + 总请求量/QPS 估算角标 | 页面实测 hasSourceTag/estimateTags=2 |

### 3.2 93bf8b9 第二批「用户体验优化 + 白名单收紧」（12 文件，+221/-39）
| # | 问题 | 修复 | 验证 |
|---|------|------|------|
| 1 | 401 整页跳钉钉登录丢上下文 | 弹窗「重新登录/留在本页」+ redirect 回跳 + 并发 401 防重弹窗 | R6 两次实测弹窗全链路 |
| 2 | 链路查询/日志搜索空态近空白 | 两页空态引导（步骤指引/使用技巧） | 页面实测 |
| 3 | 服务负载无数据新鲜度提示 | VO 增 latestStatDate/latestDataAgeDays/partialToday，滞后 ≥2 天显示标签 | 接口实测三字段 |
| 4 | 链路折算系数硬编码 | 改读 alert_threshold 配置项 gateway_chain_length（默认 20.0 兜底） | 编译 + 配置读取路径 |
| 5 | 用户行为手机号明文展示 | 检索按原文执行、展示脱敏（173****7100） | 28 条样本 0 泄漏 |
| 6 | AI 工具结果超长爆上下文 | truncateForContext 头尾保留（8800+3200）+ 截断说明 | 代码级验证 + 第 8 轮反射运行时验证（11 断言，含旁路审计） |
| 7 | README 过时描述 | 修正模块描述 | 已提交 |
| 8 | /traces/query 免登暴露 | 白名单移除 | 无 token=401、有效 token=200 |

### 3.3 34155fe 第三批「使用者视角优化」（11 文件，+245/-455）
| # | 卡点（J 动线发现） | 修复 | 验证（F1~F7） |
|---|------|------|------|
| 1 | 链路查询 0 配置且无配置入口（死路） | 菜单新增「链路配置」+ TraceQuery 空态「前往链路配置」链接 + activeIndex 高亮补齐 | 菜单 11→12 项、两处点击跳转均实测 |
| 2 | 业务监控六卡 25s 全 "-"、失败静默 | overview 独立优先加载 + v-loading + 失败 alert/重试 + 15 图表接口 Promise.allSettled | **6 秒六卡全真实数据**、10 图表全渲染 |
| 3 | 慢查询时间列 Invalid Date | formatTime 数字时间戳解析兜底（后端毫秒字符串 "1789967100062"） | 时间列 13:15:59，全页 invalidDate=false |
| 4 | 日志搜索无筛选无高亮 | 级别多选筛选（INFO/WARN/ERROR/DEBUG）+ LogViewer 关键字分段高亮（防注入，共用组件） | 筛选 20→10 条含计数、33 个 mark 高亮 |
| 5 | 下钻抽屉耗时与卡片口径矛盾无说明 | 劣化/暴涨抽屉「口径说明」tooltip | hover 取得完整文案 |
| 6 | 隐藏旧页 /user-behavior-trace 无入口 | 删除 UserBehaviorTraceQuery.vue（-402 行）+ 路由 redirect → /user-behavior | 旧 URL 重定向实测不落空白 |

## 四、测试验证体系

- **驱动**：ego-browser（Chromium 真实浏览器，任务空间隔离）；登录限制下用 CDP 预文档注入本地自签 JWT（.env.example 示例密钥，仅本地 18081 有效、1h 时效）
- **5 轮走查**：第 1~3 轮全站 11 页（DOM/JS/数据/修复点四类断言）；第 4 轮第二批优化回归 12 场景（含 R6 401 弹窗、R7 白名单收紧联动）；第 5 轮使用者视角修复验证 7 场景（F1~F7）
- **结果**：全部通过，0 白屏、0 JS 报错；截图因无头视口 0×0 受限，以 DOM 断言 + 接口 curl + 网络请求三类证据替代
- **详细证据**：见 [test-report.md](./test-report.md)

## 五、系统稳定性评估

### 5.1 已验证的稳定面
1. **页面层**：13 个路由页面 5 轮走查 0 白屏、0 未处理 JS 错误；刷新/导航/下拉交互无状态错乱。
2. **后端重启韧性**：start.sh 重启后 alert_monitor_state 建表成功、内存告警状态从 H2 恢复、新口径立即生效——**重启不丢状态**。
3. **登录态健壮性**：token 过期/缺失时 401 弹窗不跳页、可留本页继续浏览；并发 401 只弹一次（loginPromptShowing 防重）。

### 5.2 内置的弹性机制（本次评审确认/新增）
| 机制 | 位置 | 作用 |
|------|------|------|
| H2 持久化 | alert_monitor_state | 告警状态跨重启恢复 |
| 监控缓存 TTL | MiddlewareMonitorService 等 | 外部依赖抖动时返回缓存，防止雪崩放大 |
| 外部调用限流 | EXTERNAL_CALL_SEM（3 并发） | ARMS trace 详情并行查询限流 |
| **trace 详情总超时** | collectTraceResults（10s + isDone 部分收集） | 首查（缓存空）时外部依赖阻塞不再拖垮请求，超时返回已完成部分 |
| **并行批量查询总超时（铺开）** | awaitAll + getNow（第 7 轮）：MiddlewareMonitorService 9 处指标批量 60s、告警巡检/checkAll 90s、GatewayService P60 采样 60s | 全部 12 处 allOf 站点有界；@Scheduled 单线程调度池不再可能被挂起饿死；单实例失败不再丢弃整批 |
| 启动补采 | 服务负载 | 重启后历史数据回填 |
| 接口失败隔离 | biz-analysis Promise.allSettled | 15 个图表接口单点失败不拖垮六卡与全页 |
| 请求超时与防重 | axios 30s + 401 弹窗防重 | 避免长阻塞与弹窗风暴 |
| AI 上下文截断 | truncateForContext | 工具结果超长不冲爆 LLM 上下文；第 8 轮反射运行时验证 11 断言通过（12000 边界、头 8800+尾 3200 逐段一致、100 万字符输出有界、旁路审计无绕过） |
| 降噪阈值 | 劣化 <20%、暴涨 <50% | 抑制噪声告警刷屏 |

### 5.3 稳定性风险与观察项（按优先级）
1. ~~[安全] 免登白名单暴露面~~ **已闭环（第 6 轮）**：日志内容读取 /gateway/logs/query 已要求登录；/sls-keywords/query、/biz-analysis/ 已移出白名单，接口鉴权与页面权限口径对齐，8 项接口矩阵实测通过。
2. ~~[可用性] 外部依赖强耦合~~ **已闭环（第 7 轮）**：超时快速失败模式铺开至全部 12 处并行批量查询站点（指标批量 60s、告警巡检 90s、P60 采样 60s + 第 6 轮慢查询 10s），正常状态无误触发。
3. ~~[体验] biz-analysis 图表仍为一次性并发~~ **已闭环（第 7 轮）**：图表懒加载上线（IntersectionObserver rootMargin 120px），首屏仅渲染视口内 2 个图表，滚动逐步渲染至 10 个。
4. **[环境] 本地开发环境**：Vite dev server 曾出现进程退出（环境问题非代码缺陷）；测试 JWT 1 小时时效，长会话需重签。

### 5.4 稳定性结论
**当前系统处于稳定可用状态**：核心动线（大盘→下钻、劣化/暴涨→链路、用户轨迹、日志检索、告警 CRUD、业务监控）全部实测走通；重启恢复、失败隔离、降噪、防重等弹性机制就位。剩余风险集中在免登白名单收敛与外部依赖首查降级，均有明确后续方案（见 §6）。

## 六、遗留事项与后续建议
| # | 事项 | 优先级 | 说明 |
|---|------|--------|------|
| 1 | ~~/sls-keywords/query 白名单收紧~~ | ~~高~~ 已完成 | 第 6 轮收紧，无 token 401、页面带 token 正常 |
| 2 | ~~biz-analysis 接口级鉴权与页面 lianzi 限制对齐~~ | ~~中~~ 已完成 | 第 6 轮移出白名单，六卡/图表带 token 实测正常 |
| 3 | ~~外部依赖首查快速失败~~ | ~~中~~ 已完成 | 第 6 轮慢查询链路 10s；第 7 轮铺开至全部 12 处站点 |
| 4 | ~~biz-analysis 图表分组懒加载~~ | ~~低~~ 已完成 | 第 7 轮上线 IntersectionObserver 懒加载，首屏 2 个 canvas、滚动后 10 个 |
| 5 | ~~trace-query / keyword-log-query 增加最近查询记录~~ | ~~低~~ 已完成 | 第 7 轮上线（localStorage 最多 10 条去重置顶），查询记录/点击回填/清空实测通过 |
| 6 | trace_node 缺表缺陷（第 7 轮新发现，已修复） | 已闭环 | schema.sql 补 DDL；此前链路配置在任何新环境创建必 500 |

## 七、提交索引
| Commit | 日期 | 主题 | 规模 |
|--------|------|------|------|
| 48adb74 | 09-21 11:01 | 数据准确性优化（诚实化 + 降噪 10 项） | 18 文件 +567/-292 |
| 93bf8b9 | 09-21 13:32 | 用户体验优化 + /traces/query 白名单收紧（8 项） | 12 文件 +221/-39 |
| 34155fe | 09-21 14:21 | 使用者视角优化（6 项，含删除 402 行旧页） | 11 文件 +245/-455 |
| 14374b2 | 09-21 15:0x | 评审优化完整总结文档 | 1 文件 +147 |
| （本轮） | 09-21 15:2x | 日志接口鉴权 + sls-keywords/biz-analysis 白名单收紧 + 慢查询首查超时保护 | AuthFilter + MiddlewareMonitorService + 文档 |
| （第 7 轮） | 09-21 16:1x | 超时快速失败铺开 12 处 + 最近查询记录 + 图表懒加载 + trace_node 缺表修复 | 4 后端文件 + 4 前端文件 + schema.sql + 文档 |
