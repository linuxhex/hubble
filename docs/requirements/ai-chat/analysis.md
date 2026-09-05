# AI 对话功能 — 需求分析

## 背景
hubble 监控大盘目前只有表格/图表展示，运维人员无法用自然语言提问监控数据。参考 device-hubble-web / device-hubble-server 的 AI Copilot 实现，在本项目实现监控运维 AI 助手，让运维人员能自然语言提问，如"哪些接口劣化最严重？""Redis CPU 高不高？""今天流量暴涨的接口有哪些？"

## 参考项目分析（device-hubble）

### 架构
- 前端：浮动球入口 + 抽屉聊天面板，WebSocket 连接 `ws(s)://host/ws/ai/chat?token=JWT`，流式 chunk 拼接 + Markdown 渲染。
- 后端：JSR-356 `@ServerEndpoint`，LangChain4j + OpenAiStreamingChatModel（阿里云 DashScope，qwen-flash），工具调用循环，对话历史落 MySQL，内存 SessionContext + Redis 上下文。
- 协议：客户端发 `{action:"chat",message,context,pageContext}`；服务端回 `{type:"chunk"/"done"/"error"/"status"/"ping"}`。

### 与本项目的差异
| 项 | device-hubble | hubble（本项目） |
|---|---|---|
| Spring Boot | 2.7 + Java 8（javax.*） | 3.2.4 + Java 17（jakarta.*） |
| 大模型 SDK | langchain4j-open-ai 0.36.2 | dashscope-sdk-java 2.12.0（已有） |
| 认证 | JWT query 参数 | JWT query 参数（复用 JwtUtil） |
| 工具调用 | 50+ 工具 + Agent 循环 | MVP 不做，用上下文注入 |
| 对话历史 | MySQL | MVP 内存即可 |

## 本项目业务场景

已有监控接口（AI 对话的数据来源）：
- `/gateway/degradation` — 接口劣化排名
- `/gateway/traffic-surge` — 流量暴涨排名
- `/gateway/p60-ranking` — P60 耗时排名
- `/gateway/overview` — 网关概览
- `/middleware/redis/instances` — Redis 实例监控
- `/middleware/mysql/instances` — MySQL/PolarDB 实例监控
- `/middleware/pod/cpu`、`/middleware/pod/memory` — Pod 监控
- `/middleware/node/overview` — Node 监控
- `/alert-data/query` — 告警查询

## 实现方案：上下文注入 + DashScope 流式（MVP）

### 核心思路
用户提问 → 后端按关键词拉取相关监控数据 → 塞进 system prompt → DashScope 流式生成 → WS chunk 推前端。

**不做工具调用（Agent 模式）**，原因：
1. 上下文注入更简单可靠，LLM 基于真实数据回答，不编造。
2. MVP 阶段，后续可升级为工具调用。
3. 不需引入 langchain4j，复用已有 dashscope-sdk-java。

### 大模型
- SDK：dashscope-sdk-java 2.12.0（项目已有，用于 Embedding）
- 模型：qwen-turbo（默认，可配）
- 调用方式：`Generation.streamCall` + `ResultCallback`（流式）
- API Key：复用已有 `DASHSCOPE_API_KEY` 环境变量

### WebSocket
- 新增 `spring-boot-starter-websocket`
- JSR-356 `@ServerEndpoint`（jakarta.websocket，Spring Boot 3）
- 端点路径：`/ws/ai/chat`
- 认证：URL query `?token=JWT`，onOpen 用 JwtUtil 校验

### 会话历史
- 内存 `ConcurrentHashMap<sessionId, List<消息>>`，30min TTL
- 最近 5 轮拼进 system prompt
- MVP 不落 DB

## 约束
- 全中文输出
- 不编造数据：工具/数据拉取失败时如实告知
- 配置敏感值用环境变量占位（复用 DASHSCOPE_API_KEY）
- AuthFilter 放行 `/ws/ai/chat`（WS 升级握手前走 HTTP）

## MVP 范围外（后续迭代）
- 知识库向量检索
- 自学习
- 数据权限过滤
- DisplayConfig 结构化数据渲染
- 全屏 + 历史侧边栏 + 常问问题
- 工具调用（Agent 模式）
- 对话历史落 DB
