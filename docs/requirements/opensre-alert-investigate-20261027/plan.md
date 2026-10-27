# 实现计划：告警驱动的 AI 事故调查

- requirement_key: `opensre-alert-investigate-20261027`
- 工程：仅 hubble（hubble-server + hubble-web），单服务模式

## 现有基建（复用清单）

| 能力 | 位置 | 复用方式 |
|---|---|---|
| SLS 查询 | `client/SlsQueryClient` | 直接注入，查错误日志样本+count |
| ARMS 指标 | `client/ArmsClient`（queryMetricsWithDimension/appstat） | 直接注入，查告警应用接口 RT/错误率 |
| 钉钉推送 | `client/DingTalkClient.sendRobotActionCard`（含多 robot 版本） | 报告第二条推送 |
| LLM | OpenAI 兼容 HTTP，`llm.base-url/api-key/model` 配置（glm-5.2），参考 `AiChatService` | 新写非流式 `callForJson()`（HttpClient，90s 超时） |
| H2 建表 | `resources/schema.sql`（CREATE TABLE IF NOT EXISTS） | 追加两表 |
| MyBatis-Plus | entity + mapper 模式（如 `AlertMonitorState`） | 新增两套 entity/mapper |
| SSE 推送 | `AlertPushService.pushAlert` | 报告完成推送 |
| 依赖数据 | `DependencyRtAlertService` 巡检 comboKey `pid|rpcType|rpc` | 扫描处 upsert 依赖表 |

## 任务拆分（按依赖顺序）

### T1 数据层
- `schema.sql` 追加：
  - `incident_memory`（id, alert_source, alert_type, alert_name, service_name, fingerprint, severity, alert_value, status INVESTIGATING/DONE/FAILED, root_cause TEXT, analysis_summary TEXT, evidence_json TEXT, recommendation TEXT, confidence DECIMAL, error_message VARCHAR, resolved TINYINT, retry_count INT, created_at, updated_at；INDEX(fingerprint), INDEX(alert_type, service_name)）
  - `service_dependency`（id, app_name, depends_on, rpc_type, last_seen_at；UNIQUE(app_name, depends_on, rpc_type)）
- `entity/IncidentMemory.java`、`entity/ServiceDependency.java`（@TableName，对齐 AlertMonitorState 风格）
- `mapper/IncidentMemoryMapper.java`、`mapper/ServiceDependencyMapper.java`（BaseMapper，无 XML）

### T2 取证服务（4 个，互不依赖）
- `service/investigation/SlsEvidenceCollector`：按 service_name 查最近 15 分钟 `level: ERROR` 日志样本 20 条 + count；产出 `Evidence`（content, degraded=false）
- `service/investigation/ArmsEvidenceCollector`：用 ArmsClient 查该应用最近 15 分钟 appstat 指标（RT/错误率按接口），Top10 慢/错接口
- `service/investigation/DependencyEvidenceCollector`：ServiceDependencyMapper 反查 `depends_on = serviceName` 的上游调用方；再查这些上游最近是否也有告警（incident_memory 近 1h）；空图降级「依赖图数据不足」
- `service/investigation/MemoryEvidenceCollector`：IncidentMemoryService 召回 Top3 相似历史事故（权重 alert_type 匹配 0.5 + service 匹配 0.3 + resolved 0.2），返回摘要列表
- 统一接口：`EvidenceCollector.collect(InvestigationContext) -> Evidence`，各 20s 超时（CompletableFuture.orTimeout），异常降级为标注原因的空证据，绝不抛出

### T3 记忆与依赖图服务
- `service/IncidentMemoryService`：save / markDone / markFailed / findSimilar(alertType, service) / list分页 / getById
- `service/ServiceDependencyService`：upsertBatch（巡检扫到即写，ON DUPLICATE 更新 last_seen_at——H2 MySQL 模式用 MERGE 或先查后插）、findUpstream(serviceName)

### T4 脱敏
- `service/investigation/LogSanitizer`：静态工具，正则掩码：
  - IP → `10.x.x.x`→`***.***.***.***`（保留首段）；pod 名 `[\w-]+-[a-f0-9]{5,}` → `<pod>`；集群/ARN/账号 ID 长数字串 ≥8 位 → `***`
- 在 LLM 调用前对证据全文统一跑一遍

### T5 编排服务（核心）
- `service/AlertInvestigationService`：
  - `submit(InvestigationRequest)`：级别已由调用方过滤；去重键=fingerprint，为空时用 `alertSource|alertName|serviceName|30min时间桶`；**先 insert status=INVESTIGATING 再异步**，去重靠查库（含 INVESTIGATING/DONE 近 30min）；Semaphore(2) tryAcquire，拒绝则记日志丢弃并 markFailed(reason=并发满)；@PostConstruct 启动恢复：超 10 分钟仍 INVESTIGATING 的置 FAILED（防重启悬挂）；retry 走同一 submit 闸门
  - 流水线 run(investigationId)：
    1. 四路取证并行（CompletableFuture.allOf）
    2. LogSanitizer 清洗
    3. 组装 prompt（告警摘要+四路证据各限长 4k 字符+相似事故）→ LLM 非流式调用，要求 JSON 输出 `{root_cause, analysis_summary, recommendation, confidence}`
    4. JSON 解析失败重试 1 次（prompt 追加「严格 JSON」）；仍失败 markFailed
    5. markDone + 钉钉第二条（复用 ArmsWebhookService.sendDingTalk 的多 robot 逻辑模式）+ SSE
    6. 全程异常 markFailed（error_message 落库）
- LLM 调用方法放在本 service 内（baseUrl/apiKey/model @Value 同 AiChatService）

### T6 告警源接入（每个 ≤10 行）
- `ArmsWebhookService.processAlert`：`!isResolved && severity∈{ERROR,CRITICAL}` → 钉钉推送后 `investigationService.submit(...)`
- `DependencyRtAlertService.pushSurgeAlert`：推送后 submit（alert_source=DEP_RT）
- `DependencyErrorRateAlertService.pushErrorAlert`：同上（DEP_ERR）
- `MiddlewareAlertService` 红盘推送处：submit（MW，仅 red；service_name=实例标识如 `redis/prod-xxx`）
- 两个 Dependency 服务巡检循环内：`serviceDependencyService.upsertBatch(comboKeys)`（扫到即沉淀）
- 防循环：调查报告本身不触发告警（报告只推钉钉不走 webhook，天然无回路）

### T7 API + 鉴权
- `controller/IncidentInvestigationController`：
  - GET `/api/incident-investigations`（page/size/source/status 过滤）
  - GET `/api/incident-investigations/{id}`
  - POST `/api/incident-investigations/{id}/retry`（仅 FAILED 可重试，retry_count+1）
- 确认 AuthFilter 对 `/api/incident-investigations/**` 的拦截策略与其它 `/api/**` 一致（登录态）

### T8 前端「事故调查」页
- `hubble-web/src/api/incident.js`（axios 封装 3 个接口）
- `components/IncidentInvestigation.vue`：表格（时间/来源 tag/告警名/服务/级别/状态 tag/根因摘要）+ 筛选栏 + 分页；行点击开抽屉详情（告警信息、根因、分析、建议、confidence 进度条、证据折叠面板、失败原因+重试按钮）
- `router/index.js` 加 `/incident-investigation` 路由；侧边栏菜单注册（对齐现有菜单结构）

### T9 测试
- 单测：LogSanitizer 正则用例；IncidentMemoryService.findSimilar 权重；LLM JSON 解析失败重试路径
- 集成验证：本地起服务，curl 模拟 ARMS webhook payload（ERROR 级）→ 验证落库+报告生成（LLM 不可达时走 FAILED 路径也可验证兜底）
- 前端走查：列表/详情/重试渲染

## 实施顺序
T1 → T3 → T2 → T4 → T5 → T6 → T7 → T8 → T9（T2/T3 可并行）

## 验收（对应 analysis.md 成功标准）
1. 模拟 ERROR 告警 → 原告警秒发，报告第二条 ≤90s
2. 证据降级在报告中可见（如「依赖图数据不足」）
3. 同 fingerprint 重复告警不重复调查
4. 页面列表/详情/重试可用
5. 拔掉 LLM 配置模拟失败 → 原告警不受影响，调查标 FAILED 可重试
