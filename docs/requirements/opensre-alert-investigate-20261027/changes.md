# 改动简述：告警驱动的 AI 事故调查（借鉴 OpenSRE）

- requirement_key: `opensre-alert-investigate-20261027`
- requirement_id: `OMJF-00000`

## 新增（后端）
- `entity/IncidentMemory.java` — 事故记忆 + 报告存储一体实体
- `entity/ServiceDependency.java` — 服务依赖邻接表实体
- `mapper/IncidentMemoryMapper.java` — 含相似事故召回查询
- `mapper/ServiceDependencyMapper.java` — 含上游反查
- `service/IncidentMemoryService.java` — 落库 + 加权召回（alert_type 0.5 / service 0.3 / resolved 0.2）+ 启动恢复悬挂调查
- `service/ServiceDependencyService.java` — 依赖 upsert + 上游反查（爆炸半径）
- `service/AlertInvestigationService.java` — 调查编排核心：去重/信号量(2) → 四路并行取证(各20s超时降级) → 脱敏 → LLM综合分析(90s,JSON重试1次) → 落库 → 钉钉第二条+SSE
- `service/investigation/InvestigationContext.java` — 告警上下文
- `service/investigation/Evidence.java` + `EvidenceCollector.java` — 取证模型与接口
- `service/investigation/SlsEvidenceCollector.java` — SLS 错误日志样本+count
- `service/investigation/ArmsEvidenceCollector.java` — ARMS appstat.incall 接口指标 Top10
- `service/investigation/DependencyEvidenceCollector.java` — 依赖图爆炸半径+连锁告警
- `service/investigation/MemoryEvidenceCollector.java` — 历史相似事故 Top3
- `service/investigation/LogSanitizer.java` — IP/pod/ID/手机号脱敏
- `controller/IncidentInvestigationController.java` — 列表/详情/重试 3 个 API

## 新增（前端）
- `api/incident.js` — 3 个接口封装
- `components/IncidentInvestigation.vue` — 事故调查页（列表+抽屉详情+证据折叠+重试）

## 修改
- `schema.sql` — 追加 incident_memory、service_dependency 两表（CREATE TABLE IF NOT EXISTS）
- `ArmsWebhookService` — 注入调查服务；非恢复 ERROR/CRITICAL 触发 submit
- `DependencyRtAlertService` — 注入调查+依赖图服务；巡检扫到组合 upsert 依赖表；告警推送后 submit
- `DependencyErrorRateAlertService` — 同上
- `MiddlewareAlertService` — 注入调查服务；红盘推送后 submit（service_name=type/inst）
- `router/index.js` — 加 /incident-investigation 路由
- `App.vue` — 加侧边栏「事故调查」菜单项 + activeIndex

## 可靠性设计要点
- 原告警先发，调查是增强不是依赖，任何失败不影响告警推送
- 信号量(2)+30min去重防告警风暴；信号量泄漏已修（submit 异常路径补偿释放）
- 启动恢复：超 10 分钟 INVESTIGATING 置 FAILED（防重启悬挂）
- 四路取证各自超时降级，绝不中断调查
- 报告带 confidence + 「AI 仅供参考」标注，证据全落库可审计
