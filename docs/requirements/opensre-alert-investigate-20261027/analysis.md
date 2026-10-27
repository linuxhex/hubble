# 需求分析：告警驱动的 AI 事故调查（借鉴 OpenSRE）

- requirement_key: `opensre-alert-investigate-20261027`
- requirement_id: `OMJF-00000`（占位，无云效需求）
- 日期：2026-10-27
- 参考：OpenSRE（tracer-cloud/opensre）— 告警→自动调查→根因报告的模式

## 1. 背景与目标

**现状**：
- `ArmsWebhookService` 收到 ARMS 告警后仅格式化转发钉钉+SSE，不做调查
- `DependencyRtAlertService` / `DependencyErrorRateAlertService` / `MiddlewareAlertService` 自有巡检告警同样只推送
- `ChatToolService` 已有 9 个 AI 取证工具（SLS 查询/统计、ARMS 应用/指标/链路、代码检索、Nacos），`AiChatService` 已支持 tool calling——但只在用户主动对话时使用
- 每次告警独立处理，无历史事故记忆；依赖调用数据（appstat.incall）每次巡检用完即弃，未沉淀依赖图

**目标**：告警进线后自动触发调查流水线，取证（日志/指标/依赖图/历史相似事故）→ LLM 综合分析 → 结构化根因报告，异步推送钉钉并落库可查。把「告警→人去查」变成「告警→AI 先查→人看报告」。

## 2. 已确认决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| 触发范围 | ARMS Webhook + 依赖RT + 依赖错误率 + 中间件(red) 全接入 | 用户拍板「都要」 |
| 级别过滤 | 仅 ERROR/CRITICAL；resolved 恢复不调查；WARNING/yellow 走原逻辑 | 控 LLM 成本与噪声（用户选 A） |
| 推送时机 | 原告警先发（秒级）；调查异步，报告作为第二条钉钉消息，fingerprint/alertKey 关联 | 告警及时性优先，Webhook 不阻塞 |
| 调查方式 | 固定流水线（并行取证→单次 LLM 综合），不走自由 agent 循环 | 确定性、可审计、无失控风险；OpenSRE 独立评测翻车点恰在自由 agent 误归因 |
| 取证源 | SLS 错误日志 + ARMS 接口指标 + 依赖图爆炸半径 + 相似历史事故 | 数据源全部现成；Nacos/Grafana 首版不进（YAGNI） |
| 情景记忆 | H2 `incident_memory` 表；召回权重 alert_type 0.5 + service 0.3 + resolved 0.2 | OpenSRE episodic memory 口径 |
| 依赖图 | H2 `service_dependency` 邻接表（app, depends_on, rpc_type, last_seen）；依赖巡检扫描时 upsert；告警时反查上游调用方=爆炸半径 | 不引入 Neo4j |
| 脱敏 | 送 LLM 前掩码 pod 名、集群ID、IP、账号 | OpenSRE 实践，成本低 |
| 失败兜底 | 调查失败不影响原告警；报告落库标失败原因，支持重试 | 可靠性底线：调查是增强不是依赖 |
| 报告查看 | 钉钉为主 + Hubble「事故调查」页（列表+详情+手动重试） | 报告要可回溯 |
| 并发/防抖 | 同一 fingerprint/alertKey 调查中去重；全局并发上限（信号量 2）；每告警源独立冷却复用现有机制 | 防 LLM 打爆 |

## 3. 架构设计

### 3.1 核心组件（全部在 hubble-server，无新工程）

```
ArmsWebhookService ─┐
DependencyRtAlertService ─┤→ AlertInvestigationService（编排，异步）
DependencyErrorRateAlertService ─┤     ├─ 1. 级别过滤 + 去重 + 并发闸门
MiddlewareAlertService ─┘        ├─ 2. 并行取证（4 路独立 service，任一失败降级为「无证据」）
                                 │     ├─ SLS 取证：错误日志样本 + count（复用 SlsQueryClient）
                                 │     ├─ ARMS 取证：接口 RT/错误率指标（复用 ArmsClient）
                                 │     ├─ 依赖图取证：上游调用方=爆炸半径（ServiceDependencyService）
                                 │     └─ 记忆取证：相似历史事故 Top3（IncidentMemoryService）
                                 ├─ 3. 脱敏（LogSanitizer）
                                 ├─ 4. LLM 综合分析（复用现有 ModelArts/DashScope client）→ 结构化报告 JSON
                                 ├─ 5. 落库 incident_memory + 推钉钉第二条 + SSE
                                 └─ 6. 失败：落库 status=FAILED + 可重试
```

### 3.2 数据模型（schema.sql 追加，`CREATE TABLE IF NOT EXISTS`）

**incident_memory**（事故记忆 + 报告存储一体）
- id, alert_source(ARMS/DEP_RT/DEP_ERR/MW), alert_type, alert_name, service_name, fingerprint
- severity, alert_value, status(INVESTIGATING/DONE/FAILED)
- root_cause(TEXT), analysis_summary(TEXT), evidence_json(TEXT), recommendation(TEXT)
- error_message（失败原因）, resolved(TINYINT), created_at, updated_at

**service_dependency**（依赖邻接表）
- id, app_name, depends_on, rpc_type, last_seen_at；UNIQUE(app_name, depends_on, rpc_type)

### 3.3 告警源改造点（各 AlertService 只加一行钩子）

- `ArmsWebhookService.processAlert`：非 resolved 且级别∈{ERROR,CRITICAL} → `investigationService.submit(...)`；**先发原告警再 submit**（顺序不变）
- `DependencyRtAlertService.pushSurgeAlert` / `DependencyErrorRateAlertService.pushErrorAlert`：推送后 submit（阈值触发即 ERROR）
- `MiddlewareAlertService`：`level=red` 推送后 submit
- 依赖图 upsert：两个 Dependency 服务巡检扫到组合时顺手 upsert（拿现有 comboKey 拆出 app/rpc）

### 3.4 LLM 分析（单次调用，结构化输出）

Prompt 组成：告警摘要 + 四路证据（各截断限长）+ 历史相似事故（Top3 摘要）+ 输出 JSON schema（root_cause/analysis_summary/recommendation/confidence）。
- 复用现有 LLM client（ModelArts MaaS glm-5.2）
- 超时 90s；JSON 解析失败重试 1 次；仍失败 → status=FAILED

### 3.5 API + 前端

- `GET /api/incident-investigations`（分页列表：时间/来源/服务/状态/根因摘要）
- `GET /api/incident-investigations/{id}`（详情：完整报告+证据）
- `POST /api/incident-investigations/{id}/retry`（重试失败调查）
- 前端新增「事故调查」页（列表+抽屉详情+重试按钮），路由挂现有布局，Element Plus 风格一致，无新设计体系

## 4. 约束与风险

| 风险 | 对策 |
|---|---|
| LLM 误归因 | 固定流水线+证据全部落库可审计；报告带 confidence，钉钉文案注明「AI 分析仅供参考」 |
| SLS/ARMS 查询慢拖垮整体 | 四路取证并行+各自超时 20s，超时降级为「无该路证据」继续 |
| H2 单文件库压力 | 两表写入频率低（告警级），无压力 |
| 依赖图冷启动为空 | 首次告警爆炸半径标注「依赖图数据不足」，巡检跑几轮后自动补全 |
| 告警风暴 | fingerprint 去重 + 信号量(2) + 超出排队丢弃并记日志 |

## 5. 成功标准

1. 模拟一条 ERROR 级 ARMS 告警 → 钉钉先收原告警，~1 分钟内收第二条带根因报告
2. 报告四路证据齐全（或明确标注降级），结论有 evidence_json 支撑
3. 二次同 fingerprint 告警不重复调查
4. 事故调查页可看列表/详情/重试
5. 调查链路任何一步失败不影响原告警推送
