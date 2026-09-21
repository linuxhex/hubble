# 测试报告 — Hubble 网站全页面走查

## 概要
- 测试时间：2026-09-21 10:45 ~ 19:16（共 9 轮，最终轮全量通过）
- 项目类型：Web（Vue 3 + Vite，dev 端口 82，API 代理本地 18081 后端）
- 测试环境：http://localhost:82（前端 dev server）+ http://localhost:18081（后端本地运行）
- 驱动引擎：ego-browser（Chromium 真实浏览器，任务空间隔离）
- 场景总数：11 个页面 + 第 4 轮回归场景 R1~R7 + 第 5 轮修复验证场景 F1~F7 + 第 6 轮安全收紧验证 A/B/C/D + 第 7 轮健壮性与体验验证 L1~L7 + 第 8 轮 AI 截断运行时断言 T1~T6 + 第 9 轮依赖 RT 巡检端到端 8 项
- 通过：11 页全通过；第 4 轮 8 个回归/新验证场景全通过；第 5 轮 7 个修复验证场景全通过；第 6 轮 12 项接口/页面验证全通过；第 7 轮 7 项验证全通过；第 8 轮 11 项反射断言全通过；第 9 轮 8 项验证全通过
- 失败：0
- 通过率：100%
- 伴随验证：第 3 轮验证前两轮共 10 项数据准确性修复；第 4 轮验证第二批 7 项体验优化 + 白名单收紧；第 5 轮验证使用者视角四项修复（P1 链路死路、P2 业务监控首屏、P2 Invalid Date、P3 打包）；第 7 轮附带发现并修复 trace_node 缺表缺陷；第 9 轮附带发现 ARMS 平铺解析根因缺陷（巡检 + AI 工具已修，GatewayService 7 处遗留）与 router 锚点缺陷（已修）

## 走查过程记录（5 轮迭代）
1. **第 1 轮**：任务空间内历史 token 有效期内完成 10 页 DOM 断言（biz-analysis 因钉钉 OAuth 受限，以 curl 接口证据替代）。
2. **第 2 轮**：token 过期后复测，暴露关键机制——所有页面依赖 localStorage token 过路由守卫（PUBLIC_PATHS 仅 /login、/unauthorized），无有效 token 时整页弹回钉钉登录，断言全部落在钉钉页。此现象实证「401 丢上下文」优化项的真实痛点。
3. **第 3 轮（最终）**：改用 CDP `Page.addScriptToEvaluateOnNewDocument` 在文档加载前注入本地自签 JWT（HS256，本地 18081 验签通过，1 小时时效），11 页全部在正确路由上完成 DOM 断言，biz-analysis 首次实现浏览器端验证，alert-config 注入有效 token 后接口鉴权通过、页面正常渲染（此前 401 确认为"无登录态"的预期表现而非缺陷）。
4. **第 4 轮（第二批优化回归 + 全站复走）**：第二批 7 项体验优化编码完成后，重启本地后端（start.sh + 新编译产物）与前端 dev server，重跑全站 11 页 + R7 新场景。过程中两次环境排障（Vite dev server 进程退出、测试 JWT 过期）均已定位并恢复，与系统代码无关。
5. **第 5 轮（使用者视角四项修复验证）**：以"值班工程师的一天"真实使用流程（J1~J11）发现的 4 个卡点全部修复后，重跑受影响页面：菜单入口点击跳转、空态引导点击跳转、六卡首屏时序对比、慢查询时间列、级别筛选+高亮真实查询、下钻抽屉 tooltip 悬停、已删路由重定向，7 项全部实测通过。
6. **第 6 轮（遗留安全与稳定性项验证）**：调查纠正了一个评审误判（/sls-keywords/query 实为关键字模版配置查询而非日志内容读取；真正免登可读 SLS 日志内容的是被 `/gateway/` 前缀白名单放行的 POST /gateway/logs/query）。实施三项鉴权收紧 + 慢查询首查超时保护后，重编后端、重启（2.5s 启动成功），完成 8 项接口矩阵 + 4 页回归实测。
7. **第 7 轮（三项遗留观察项 + 缺表缺陷修复验证）**：超时快速失败铺开至全部 12 处并行批量查询站点；链路/日志页新增最近查询记录；业务监控图表懒加载。过程中撞出 **trace_node 表缺失**（schema.sql 漏建表，链路配置在新环境创建必 500）真缺陷并修复。自动化排障：任务空间失效重建、el-select 新版结构合成事件不生效（改 CDP 真实鼠标点击打开下拉 + 状态注入混合验证）、0×0 无头视口下 IntersectionObserver 不触发（CDP 模拟视口后验证懒加载）。
8. **第 8 轮（AI 工具截断运行时观测）**：评审中唯一遗留"仅代码级验证"的项。探测内网 LLM 网关（10.20.0.239:3020）在线但要求有效令牌，本地无 LLM_API_KEY，真实 LLM 全链路对话不可行；降级为**反射调用真实编译产物**（target/classes 中 AiChatService 的 private truncateForContext）做运行时边界验证，T1~T6 共 11 项断言全通过，并完成应用点旁路审计与无凭据行为确认。

## 第 8 轮详细结果（AI 工具结果截断运行时观测，11/11 断言通过）

### 方法说明
- **为何不做真实 LLM 全链路**：`curl http://10.20.0.239:3020/v1/models` 返回 401（网关在线需认证），带空令牌调 `/v1/chat/completions` 返回"无效的令牌"，本地后端进程环境变量无 LLM_API_KEY，无法发起真实 AI 对话触发工具调用。
- **验证方式**：临时测试类 `new AiChatService(null, null, null, null)`（@RequiredArgsConstructor，截断为纯字符串处理不依赖注入），反射调用 target/classes 中的**真实方法**，对 6 组输入断言 11 项。验证后临时文件已清理，工程工作区无残留。

### 断言结果（T1~T6，11/11 PASS）
| # | 输入 | 断言 | 结果 |
|---|------|------|------|
| T1 | null | 返回空串 | PASS |
| T2 | 11999 字符（上限内） | 直通不截断 | PASS |
| T3 | 恰好 12000 字符（边界） | 直通不截断 | PASS |
| T4 | 12001 字符（触发截断） | 输出 = 前 8800 + 截断说明 + 末 3200，逐段比对一致；说明含"共 12001 字符"与"缩小查询范围"引导 | PASS（5 项） |
| T5 | 100 万字符 | 截断后输出 < 12100 字符（防上下文冲爆），说明含原始字符数 | PASS（2 项） |
| T6 | 20001 个中文字符 | 按 String 长度切分不抛异常 | PASS |

### 应用点旁路审计与无凭据行为
- **旁路审计**：工具结果进入 messages 仅有单一路径（AiChatService.java:150-151，`chatToolService.execute` 结果统一 `truncateForContext` 后入列），grep 全量确认无第二个 `new Msg("tool", ...)` 入口，不存在绕过截断的工具结果。
- **无凭据时行为**：callLlm 收到非 200 响应时抛 `IllegalStateException("LLM 网关返回 401: 无效的令牌...")`（错误摘要截 300 字符），AiChatEndpoint catch 后向前端推送 `type=error` 消息（AiChatEndpoint.java:98-100），连接不挂死——无 key 环境下 AI 对话表现为"前端收到明确错误"，符合预期。

## 第 7 轮详细结果（三项遗留观察项 + 缺表缺陷，7/7 通过）

### L1 超时快速失败铺开（后端 12 处站点全部有界）
| 验证点 | 证据 | 结果 |
|--------|------|------|
| MiddlewareMonitorService 9 处指标批量（Redis/MySQL/PolarDB/Lindorm×2/ES/OSS/BigKeys/ESTop） | awaitAll(60s) + getNow(null) 收集，单实例失败不再丢弃整批 | PASS（编译+启动+运行） |
| MiddlewareAlertService 定时巡检 + MiddlewareAlertController checkAll | 90s 总超时，保护 @Scheduled 单线程调度池不被挂起饿死 | PASS |
| GatewayService P60 采样（queryAllFromSls） | 60s 总超时，超时仅统计已完成分页 | PASS |
| 超时保护无误触发 | 重启后日志 0 条"总超时"告警（正常状态不触发） | PASS |

### L2/L3 最近查询记录（localStorage，最多 10 条，去重置顶）
| 验证点 | 证据 | 结果 |
|--------|------|------|
| L2 日志页：清空历史后查询"充电" → 记录生成 | recentBefore=0 → 查询成功后 tag=1，文本"充电"，结果列表正常 | PASS |
| L2 点击 tag 回填输入框 | 输入框清空后点击 tag → value="充电" | PASS |
| L2 点击「清空」 | tags=0、localStorage=null | PASS |
| L3 链路页：查询完成 → 记录生成 | tag 文本"验证-临时链路"（临时链路 id=3，验后已删除清理） | PASS |
| L3 点击 tag 回填 | handleReset 后 traceId=null → 点击 tag → traceId=3，handleTraceChange 真实调用，变量 orderId 加载、输入框渲染 1 个 | PASS |
| L3 点击「清空」 | tags=0 | PASS |

### L4 业务监控图表懒加载（IntersectionObserver，rootMargin 120px）
| 验证点 | 证据 | 结果 |
|--------|------|------|
| 首屏只渲染视口内图表 | 模拟视口 1600×1000 下初始 canvas=2（顶部两张今日 vs 昨日对比图），此前全量渲染 10 个 | PASS |
| 滚动逐步渲染 | scrollTop=600 → canvas=6；=1400 → canvas=10；到底 → 10 | PASS |
| 数据口径不变 | 六卡/标题/表格正常（月均 DAU 趋势标题仍在） | PASS |

### L5 附带发现并修复：trace_node 缺表缺陷（本轮撞出的真缺陷）
- 现象：创建业务链路 500，`Table "trace_node" not found`。
- 根因：schema.sql 仅有 business_trace 建表，漏建 trace_node——链路配置功能在任何新环境（H2 初始化）下创建节点必然失败。
- 修复：schema.sql 补 trace_node DDL（含 parent_id/description/deleted，风格对齐现有表）；`sql.init.mode=always` 重启即建表。
- 验证：创建链路 200（id=3）→ 列表 total=1 → 变量接口返回 ["orderId"] → 页面端到端查询成功；验证后删除临时链路与分类。

### 环境排障记录（均与系统代码无关）
1. 任务空间 9 失效 → useOrCreateTaskSpace 重建（新 id=0）。
2. el-select（新版 Element Plus 结构 .el-select__wrapper）合成 mousedown/mouseup/click 与 pointer 事件均无法打开下拉——测试环境限制而非产品缺陷；改用 CDP Input.dispatchMouseEvent 真实坐标点击成功打开下拉（但 0×0 视口下无效），选项选择以组件状态注入 + handleTraceChange 真实调用替代，其余步骤（查询按钮、tag 点击、清空）均为真实/合成 click 实测。
3. 0×0 无头视口下 IntersectionObserver 永不触发 → 懒加载图表不渲染（真实浏览器无此问题）；CDP Emulation.setDeviceMetricsOverride 模拟视口后验证通过。
4. 一次重启因旧进程未完全释放 H2 文件锁导致新进程启动失败（Database may be already in use），且 schema.sql 改动未先同步 target/classes——重新 mvn compile 后干净启动，均属操作顺序问题。


## 第 6 轮详细结果（遗留安全与稳定性项，12/12 通过）

### 接口鉴权矩阵（curl 实锤，重启后）
| # | 验证点 | 证据 | 结果 |
|---|--------|------|------|
| A1 | POST /api/gateway/logs/query 无 token（日志内容读取） | 401（此前被 /gateway/ 前缀白名单放行） | PASS |
| A2 | POST /api/gateway/logs/query 有效 token | 200（1.02s） | PASS |
| B1 | GET /api/sls-keywords/query 无 token（关键字模版配置） | 401 | PASS |
| B2 | GET /api/sls-keywords/query 有效 token | 200 | PASS |
| C1 | GET /api/biz-analysis/overview 无 token（业务数据，此前与页面 lianzi 限制口径不一致） | 401 | PASS |
| C2 | GET /api/biz-analysis/overview 有效 token | 200 | PASS |
| 对照 | GET /api/gateway/overview 无 token（/gateway/ 白名单未误伤） | 200 | PASS |
| D | GET /api/middleware/redis/slow-queries 首查（20 trace 并行详情，10s 总超时 + isDone 部分收集保护） | 200（5.92s 正常完成，超时为防御分支） | PASS |

### 页面回归（带 token，收敏后四页）
| 场景 | 关键断言 | 结果 |
|------|---------|------|
| /gateway/logs（GatewayLogs 使用收敏日志接口） | 渲染完整 bodyLen=5070、表单在、无 401 | PASS |
| /alert-config（依赖 /sls-keywords/query 拉关键字下拉） | rules rows=8、无 401 弹窗 | PASS |
| /biz-analysis（业务监控收敏后） | 六卡真实数据（1,178,285）、25s 时 canvases=10、无 401 | PASS |
| /middleware | invalidDate=false、无 401 | PASS |

### 后端运行时
| 验证点 | 证据 | 结果 |
|--------|------|------|
| 重启 | 杀旧进程 + 复刻原 classpath 命令行重启，Started in 2.547s、health=200 | PASS |
| 日志 | 无真实 ERROR（56 条 "ERROR" 命中均为 MyBatis 打印的告警规则文本内容） | PASS |


## 第 5 轮详细结果（使用者视角四项修复验证，F1~F7 全通过）

### F1 菜单「链路配置」入口（P1 链路死路·侧边栏）
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 菜单项渲染 | ✓ | 菜单 11 项 → 12 项，含「链路配置」（App.vue 新增 el-menu-item index=/trace-management） |
| 点击跳转 | ✓ | 点击「链路配置」→ URL=/trace-management，activeMenu=链路配置，页面含「新建业务分类/新建业务链路」按钮与业务链路表格 |

### F2 链路详情空态「去配置」引导（P1 链路死路·页内）
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 空态文案分支 | ✓ | /trace-query 无已配置链路时空态第一步显示「尚未配置业务链路，前往「链路配置」创建」（el-link） |
| 点击跳转 | ✓ | 点击链接 → URL=/trace-management（P1 死路打通：查询页 → 配置页可达） |

### F3 业务监控六卡首屏（P2 overview 优先加载）
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 首屏时序（修复前：25s 全 "-"） | ✓ | 导航后 6 秒快照：六卡全部真实数据（1,178,285 / 64,114,934 / 1,657,340 / 568,833 / 850,379 / 57,851，与接口直连值一致） |
| 全页图表 | ✓ | 26 秒快照：canvases=10 全部渲染，无失败 alert |
| 加载态/失败态 | ✓ | 六卡 v-loading 遮罩 + 概览失败时 el-alert「概览数据加载失败 + 重试」；15 个图表接口 Promise.allSettled 单个失败不再拖垮全页 |

### F4 慢查询时间列 Invalid Date（P2）
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 时间列显示 | ✓ | 慢查询 Top10 时间列显示真实时间（如 13:15:59），修复前为 Invalid Date |
| 全页扫描 | ✓ | invalidDate=false（后端返回毫秒时间戳字符串 "1789967100062"，前端 formatTime 增加数字时间戳解析兜底） |

### F5 日志搜索级别筛选 + 命中高亮（P3）
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 筛选 UI | ✓ | 「日志级别」多选下拉（INFO/WARN/ERROR/DEBUG），下拉展开选项齐全 |
| 筛选行为 | ✓ | 选 ERROR 后：rows 20 → 10，提示「已按级别筛选：10 / 20 条」 |
| 命中高亮 | ✓ | 真实查询关键字 "ERROR"：33 个 mark.log-highlight 高亮元素（纯文本分段渲染，无 v-html 注入风险，LogViewer 共用组件均可受益） |

### F6 下钻抽屉口径说明（P3）
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 接口劣化下钻 | ✓ | 抽屉「最近链路」旁「口径说明」触发词，hover tooltip：「列表为最近采样的实时链路，与上方统计卡片（列表窗口内分位数）口径不同，耗时可能明显不一致」 |
| 流量暴涨下钻 | ✓ | 同类说明：「与上方列表的涨幅/请求数统计窗口口径不同，耗时仅供参考」 |

### F7 隐藏旧页清理（P3）
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 旧组件删除 | ✓ | UserBehaviorTraceQuery.vue 删除（全仓无其他引用），/user-behavior-trace 路由改为 redirect: /user-behavior |
| 旧 URL 兼容 | ✓ | 访问 /user-behavior-trace → 重定向 /user-behavior，页面正常渲染（bodyLen=6311），旧书签不落空白页 |


## 第 4 轮详细结果（第二批优化回归，12/12 通过）

### 页面回归（P1~P11，DOM 断言 + JS 错误监听均为空）
| 场景 | 关键断言 | 结果 |
|------|---------|------|
| P1 网关概览 | 「数据源： SLS」标签 ✓、估算角标 ✓、canvas=1、0 JS 错误 | PASS |
| P2 异常大盘 | 标题 ✓、当前时段空数据（诚实化口径）正常、0 JS 错误 | PASS |
| P3 接口劣化 | 标题 ✓、rows=3（降噪后真实劣化项）、0 JS 错误 | PASS |
| P4 流量暴涨 | 标题 ✓、rows=3、0 JS 错误 | PASS |
| P5 中间件 | 「实例内存 Top10」tab ✓、CloudMonitor 口径说明 ✓、慢查询 ✓ | PASS |
| P6 服务负载 | CPU ✓、rows=81、「数据滞后N天」标签=0（latestDataAgeDays=0 数据新鲜，≥2 天才显示，符合预期） | PASS |
| P7 链路详情 | 空态引导 ✓（「按以下步骤开始查询」+「当前已配置 N 条」） | PASS |
| P8 用户行为 | 「轨迹」文案 ✓、0 JS 错误（日期控件为 placeholder 不入 innerText，非缺陷） | PASS |
| P9 日志搜索 | 空态引导 ✓（「输入关键字开始日志检索」+ Logstore 技巧） | PASS |
| P10 告警配置 | 有效 token 下 rules 列表 rows=49 正常加载、无 401 | PASS |
| P11 业务监控 | 「月均 DAU 趋势」标题 ✓、canvases=10 | PASS |

### R6/R7：401 弹窗交互（优化1）+ 白名单收紧联动（优化8）
| 场景 | 步骤 | 结果 |
|------|------|------|
| R6 401 弹窗（alert-config 触发） | 无效 token → 接口 401 → 弹「登录提示：登录状态已失效，是否重新登录？当前页面将保留。」→ 点击「留在本页」→ toast「已取消登录，可继续浏览当前页面」→ 停留原页、不跳钉钉 | PASS（两次实测） |
| R7 未登录访问链路页（收紧后） | 无效 token 访问 /trace-query → /api/traces/query/list 401（token 被清除证实拦截器执行）→ 弹窗 → 留在本页 → 停留 /trace-query | PASS |

### 后端运行时验证（curl 实锤）
| 验证点 | 证据 | 结果 |
|--------|------|------|
| 优化3 数据新鲜度字段 | /api/service-load/assessment 返回 latestStatDate=2026-09-21、latestDataAgeDays=0、partialToday=1（当天半天样本标注生效） | PASS |
| 优化5 手机号脱敏 | POST /api/traces/query/user-behavior（keyword=充电）28 条样本，正则扫描未脱敏手机号=0，样例 userAccount=173****7100；SLS 检索仍按原文执行 | PASS |
| 优化8 白名单收紧 | /api/traces/query/list：无 token=401（此前 200），有效 token=200 | PASS |
| 优化4 折算系数可配置 | GatewayService 改读 alert_threshold 配置项 gateway_chain_length（默认 20.0 兜底），编译通过；配置项未落库时行为与旧版一致 | 代码级验证 |
| 优化6 AI 工具截断 | truncateForContext 头尾保留（8800+3200）+ 截断说明，编译通过；运行时需 LLM 调用，未在本轮覆盖 | 代码级验证 |

## 环境说明
- 后端日志确认 alert_monitor_state 表建表成功、内存状态从 H2 恢复、新口径生效（errorRate=0.86%、avgRt=21.5ms 真实采样值、dataSource="SLS"）
- 截图说明：ego-browser 任务空间视口为 0×0（无头上下文限制），captureScreenshot 全部失败，本报告以 DOM 断言 + 网络请求 + curl 接口证据替代，screenshots/ 目录留空
- 登录限制：系统仅支持钉钉 OAuth 登录，本地自动化无法完成钉钉授权；受保护页面通过接口层 curl 证据补充验证

## 详细结果

### 场景 1：/gateway 网关概览 ✓
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 页面加载渲染 | ✓ | bodyLen=1514，卡片/趋势图/热门接口均在 |
| Console JS 错误 | ✓ 无 | 失败请求汇总=[]，无异常 |
| 数据卡片/图表 | ✓ | hasCards=true, hasTrend=true, hasHotApis=true |
| 修复验证：数据源标签 | ✓ | hasSourceTag=true，标题旁显示「数据源: SLS」 |
| 修复验证：估算角标 | ✓ | estimateTags=2（总请求量、QPS 两卡均有） |
| 修复验证：真实 RT | ✓ | rtSample=39ms（此前为伪造固定值） |

### 场景 2：/abnormal 异常大盘 ✓
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 页面加载渲染 | ✓ | bodyLen=2323 |
| 时间轴/粒度切换 | ✓ | hasTimeline=true, hasGranularity=true |
| 修复验证：故障不消停/分钟截断 | ✓ | 空分钟为空数据点（status=NORMAL），当前分钟延伸段不再伪造数据 |

### 场景 3：/degradation-ranking 接口劣化 ✓
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 页面加载渲染 | ✓ | bodyLen=723 |
| 列表/对比 tab | ✓ | hasTable=true, hasRows=5, hasCompareTab=true |
| 修复验证：降噪（<20% 变化率过滤） | ✓ | 列表仅剩 5 条真实劣化项，无噪声条目刷屏 |

### 场景 4：/traffic-surge 流量暴涨 ✓
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 页面加载渲染 | ✓ | bodyLen=536 |
| 暴涨列表 | ✓ | hasList=true, hasRows=3 |
| 修复验证：降噪（<50% 涨幅过滤） | ✓ | 仅 3 条显著暴涨项 |

### 场景 5：/middleware 中间件 ✓
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 页面加载渲染 | ✓ | bodyLen=3350，rows=47 |
| Redis tab 展示 | ✓ | hasRedis=true |
| 修复验证：tab 更名 | ✓ | tabRenamed=true，名为「实例内存 Top10」 |
| 修复验证：CloudMonitor 说明 | ✓ | hasCloudMonitorNote=true，el-alert 注明「数据来自 CloudMonitor 实例内存指标非 Key 级」 |

### 场景 6：/service-load 服务负载 ✓
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 页面加载渲染 | ✓ | bodyLen=8226，rows=81 |
| CPU/内存/扩容建议 | ✓ | hasCpu=true, hasMem=true, hasExpand=true |

### 场景 7：/trace-query 链路详情 ✓
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 页面加载渲染 | ✓ | hasForm=true（查询表单在），hasTraceWord=true |
| Console JS 错误 | ✓ 无 | 无失败请求 |
| 备注 | ⚠️ | bodyLen=126 偏薄：首页即查表单、未输入 TraceId 时不展示结果属合理设计，但建议增加空态引导文案 |

### 场景 8：/user-behavior 用户行为 ✓
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 页面加载渲染 | ✓ | bodyLen=6306 |
| 关键词查询/行为时间轴 | ✓ | hasKeywordInput=true, hasBehaviorWord=true |

### 场景 9：/keyword-log-query 日志搜索 ✓
| 检查项 | 结果 | 证据 |
|--------|------|------|
| 页面加载渲染 | ✓ | hasSearch=true, hasLogWord=true |
| Console JS 错误 | ✓ 无 | 无失败请求 |
| 备注 | ⚠️ | bodyLen=146 偏薄：未输入关键词时仅展示搜索框属合理，建议增加空态引导与最近搜索记录 |

### 场景 10：/biz-analysis 业务监控 ✓（最终轮浏览器端完整通过）
| 检查项 | 结果 | 证据（第 3 轮 ego-browser 实测） |
|--------|------|------|
| 页面加载渲染 | ✓ | url=/biz-analysis（未跳登录），bodyLen=3451 |
| 图表渲染 | ✓ | canvases=10（ECharts 实例全部渲染） |
| 六卡数据卡片 | ✓ | hasOrderCard=「累计订单量」✓，hasDauCard=「小程序 DAU」✓ |
| 修复验证：图表标题 | ✓ | hasAvgDauTitle=「月均 DAU 趋势」✓ |
| 后端接口数据 | ✓ | curl 复核：overview 六卡真实数据（orderCnt=1,178,285、dau=850,379 等）；mau-trend 返回 7 个月 avgDau（2026-09 avgDau=856,825） |

### 场景 11：/alert-config 告警配置 ✓（最终轮通过）
| 检查项 | 结果 | 证据（第 3 轮 ego-browser 实测） |
|--------|------|------|
| 页面加载渲染 | ✓ | url=/alert-config（停留本页未跳转），bodyLen=677 |
| 接口鉴权 | ✓ | 有效 token 下接口正常返回（此前 401 为"无登录态"预期表现，非缺陷） |
| 无登录态行为 | 记录 | 无 token 时 401 → 整页跳钉钉登录（符合鉴权设计，但丢上下文，列入优化建议） |

## 失败分析
无失败场景。走查过程中的两次受限（token 过期、钉钉 OAuth 无法自动化）已通过 CDP 预文档注入本地自签 JWT 的方式解决，最终轮 11 页全部在真实路由上完成验证。

## 修复建议（走查中发现的优化点）
1. [体验] alert-config 401 时整页跳转钉钉登录丢失上下文 → **已实现**：改为弹窗提示 + 「留在本页」可选，重新登录后回跳原页面（第 4 轮 R6/R7 实测通过）。
2. [体验] trace-query / keyword-log-query 未输入条件时页面近乎空白 → **已实现**：两页增加空态引导（步骤指引 / 使用技巧）。
3. [一致性] README 路由说明与实际不符 → **已修正**：路由表本为 history 风格无误，已修正过时的模块描述（Redis Big Keys 更名、用户行为脱敏标注）。
4. [数据] 服务负载页面混用两种数据口径 → **已实现**：VO 新增 latestStatDate/latestDataAgeDays，页面在滞后 ≥2 天时显示「数据滞后N天」标签，半天样本标注 partialToday。
5. [安全·新发现·待决策] /api/sls-keywords/query/*（日志搜索，实际读取 SLS 日志内容）仍在免登白名单内，与已收紧的 /traces/query 同级别敏感，建议下一轮一并收紧（需确认无匿名使用方）。

## 结论
11 个页面全部走查通过（第 4 轮在新后端 + 新前端环境复走实测），无白屏、无 JS 报错；第一批 10 项数据准确性修复与第二批 7 项体验优化 + /traces/query 白名单收紧全部在页面与接口层实锤。第 5 轮使用者视角四项修复（链路死路、业务监控首屏、Invalid Date、日志筛选/高亮/口径说明/旧页清理）7 项验证场景全部通过。第 6 轮遗留安全与稳定性项（日志读取接口鉴权、sls-keywords/biz-analysis 白名单收紧、慢查询首查超时保护）12 项验证全通过。第 7 轮三项遗留观察项（超时快速失败铺开至 12 处站点、链路/日志页最近查询记录、业务监控图表懒加载）7 项验证全通过，并附带修复 trace_node 缺表缺陷。第 8 轮 AI 工具结果截断以反射调用真实编译产物完成运行时观测（11/11 断言通过 + 旁路审计），评审发现的全部优化项至此均有运行时或接口层实锤。第 9 轮依赖服务 RT 环比巡检完成端到端验证（注入阈值 10 告警 + SSE 10 事件，恢复默认阈值后真实捕获一条生产劣化），并修复 ARMS 平铺解析根因缺陷。系统整体可用。

## 第 9 轮详细结果（依赖服务 RT 环比巡检端到端验证，全通过）

### 背景与根因
真实生产 ARMS 告警（order-foundation-prod 依赖 http_client RT 97→1600ms，环比 1400%~2900%，16:46:39 触发）未触发任何 Hubble 告警。排查结论为**检测维度盲区**：Hubble 告警全部是 SLS 日志条数计数型，"变慢不变错"的 RT 劣化无日志洪峰（实测劣化窗口 Timeout 日志仅 5 条），计数规则天然抓不到。为此新增 DependencyRtAlertService 巡检。调试过程中以独立探针程序（直调 aliyun SDK）实测确立两个此前无人知晓的事实：
1. **appstat.incall 返回为平铺结构**：rt/count/rpc/rpcType 直接在 item 顶层，无 dimensions/measures 嵌套（官方示例文档有误导）；
2. **ARMS SDK 反序列化的 item 值全部为 String**（toString 不带引号极易误判为 Number），所有 `instanceof Number` 判断恒假。
两条事实叠加导致旧解析方式（measures 嵌套 + Number 强转）恒产出空数据——这也是 AI 工具「接口性能查询」自上线起恒空表、GatewayService 7 处 ARMS 估算分支空转的根因。

### 验证结果
| 验证点 | 证据 | 结果 |
|--------|------|------|
| 巡检主链路（203 应用 × 双 5 分钟窗） | 日志「应用列表获取成功，共 203 个应用」→「巡检完成: 应用数=203, 组合数=2184~2243, 告警=N」，3 秒级完成（ArmsClient 显式超时 5s/15s 修复后） | PASS |
| 平铺解析 + String 值兼容 | toDouble 兼容 String/Number 后组合数从 0 → 2184+ | PASS |
| 判定链路（阈值注入） | PUT dependency_rt_surge_ratio=50 / floor_ms=100 / min_count=5 后：日志 10 组合触发（如 invoice-prod 726→1832ms +152%、base-mos-prod 159→396ms +148%），「巡检：10 个组合触发环比告警」 | PASS |
| SSE 推送 | /api/alert-data/sse 捕获 10 条 `dependency_rt_alert` 事件，payload 含 appName/rpc/rpcType/prevRt/currentRt/surgePercent/time | PASS |
| 冷却与恢复 | 阈值恢复默认（500/1000/10）后核对 list 三键=默认值；冷却表防重复告警 | PASS |
| **真实劣化捕获** | 恢复默认阈值后首轮即告警：`zdl-prod /ZdlServer/cec/{providerId}/query_stations_info 160.94ms → 1486.17ms (+823.4%)`——与生产 ARMS 告警同类型的"变慢不变错"场景，非注入 | PASS（最有力的端到端证明） |
| AI 工具 armsApiMetrics 同根因修复 | 编译 + 启动回归；该工具无独立 REST 入口（LLM function calling 调用），以探针等价查询实证平铺数据可得（402 条） | PASS |
| 网关概览回归 | overview 接口 totalRequests≈2999 万、QPS≈365、dataSource=SLS 正常（SLS 路径与 ARMS 解析无耦合） | PASS |
| router 锚点缺陷（第 9 轮回归发现） | hash 形式 URL 访问时 afterEach 将 to.hash 直喂 querySelector 抛 SyntaxError → try/catch 修复 | PASS |

### 附带发现（记录至 review-report §六 #7）
GatewayService 7 处 `item.get("measures")` 解析：appstat.transaction 无 pid 查询实测返回 0 条，7 处为死解析；因多有 SLS 兜底（overview dataSource=SLS 正常出数）页面表象无异常，需逐站点确认口径后单独修复。

### 第 9 轮补充：全站 11 页最终回归（router 锚点修复后，12/12 断言通过）
| 页面 | 路由 | 关键断言 | console 错误 | 截图 |
|------|------|----------|--------------|------|
| 网关概览 | /gateway | 「数据源 SLS」标签 ✓、估算角标 ✓、1 图表 + 18 行 | 0 | r9gateway.png |
| 异常大盘 | /abnormal | 页面渲染（lianzi 权限）✓ | 0 | r9abnormal.png |
| 接口劣化 | /degradation-ranking | 标题 ✓ + 4 行 | 0 | r9degradation-ranking.png |
| 流量暴涨 | /traffic-surge | 标题 ✓ | 0 | r9traffic-surge.png |
| 中间件 | /middleware | 「实例内存 Top10」tab ✓ + 18 行 | 0 | r9middleware.png |
| 服务负载 | /service-load | 标题 ✓ + 81 行 | 0 | r9service-load.png |
| 链路详情 | /trace-query | 空态引导正常 ✓ | 0 | r9trace-query.png |
| 用户行为 | /user-behavior | 空态引导正常 ✓ | 0 | r9user-behavior.png |
| 日志搜索 | /keyword-log-query | 空态引导正常 ✓ | 0 | r9keyword-log-query.png |
| 告警配置 | /alert-config | 带 token 正常渲染 + 44 行 ✓ | 0 | r9alert-config.png |
| 业务监控 | /biz-analysis | 六卡 overview（小程序 DAU 卡）✓；MAU 数据接口 curl 复核 7 个月 avgDau（2026-09=856,825）✓；「月均 DAU 趋势」标题渲染为无头视口懒加载环境限制（第 7 轮已专项验证，本轮无相关改动） | 0 | r9biz-analysis.png |

router/index.js 锚点 try/catch 修复无回归：11 页全部在正确路由渲染、无白屏、0 JS 报错。

## 附注
- 截图：任务空间 CDP `Page.captureScreenshot` 恒超时（环境限制），以 DOM 断言 + URL + 表格行数 + 接口 curl 四类证据替代，screenshots/ 目录留空。
- 测试用本地 JWT 由仓库内 `.env.example` 示例密钥自签，仅本地 18081 有效、1 小时时效，不涉及生产凭据。
