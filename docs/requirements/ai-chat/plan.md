# AI 对话 + 中间件监控扩展 — 实现计划

## Context
两件事一起做：
1. **AI 对话**：参考 device-hubble 的 AI Copilot，在 hubble 监控大盘实现监控运维 AI 助手（浮动球 + 抽屉聊天 + WebSocket 流式），用上下文注入方式让 LLM 基于真实监控数据回答。
2. **中间件监控扩展**：当前只监控 Redis/MySQL/PolarDB/Pod/Node，新增 RocketMQ、Kafka、Lindorm 三类阿里云托管中间件监控，并接入 AI 对话上下文。

---

## 一、中间件监控扩展

### 现状
- 已监控：Redis（acs_kvstore）、MySQL/PolarDB（acs_rds_dashboard）、Pod、Node（Grafana/PromQL）
- `CloudMonitorClient.queryMetric(namespace, metric, dim, period, start, end)` 是通用方法，只要知道 namespace + 指标名 + 维度就能查。
- 实例列表：Redis/RDS 用各自 SDK（`aliyun-java-sdk-r-kvstore` / `aliyun-java-sdk-rds`）。

### 新增中间件（阿里云 CloudMonitor namespace）

| 中间件 | namespace | 实例列表方式 | 核心指标 |
|--------|-----------|-------------|---------|
| **RocketMQ** | `acs_mq` | 配置实例 ID（无 SDK） | 消息堆积量 `MessageAccumulation`、生产 TPS `SendTps`、消费 TPS `ConsumeTps` |
| **Kafka** | `acs_kafka` | 配置实例 ID | 消息堆积 `Lag`、生产 TPS `ProduceTps`、消费 TPS `ConsumeTps` |
| **Lindorm** | `acs_lindorm` | 配置实例 ID | CPU `CpuUsage`、存储使用率 `DiskUsage`、QPS `Qps` |
| **Elasticsearch** | `acs_elasticsearch` | 配置实例 ID | CPU `NodeCPUUtilization`、磁盘 `NodeDiskUtilization`、JVM 内存 `NodeJVMMemoryUsedPercent` |
| **OSS** | `acs_oss` | 配置 Bucket | 请求次数 `TotalRequestCount`、带宽 `InternetSendBytes`、4xx/5xx 错误率 |

> 实例 ID 通过 `application.yml` 配置（仅生产环境实例，用 CloudMonitor 通用 `queryMetric` + 配置的实例 ID/维度即可，无需新 SDK）。

### 后端改动

#### `application.yml` — 新增中间件实例配置
```yaml
middleware:
  rocketmq:
    instances:
      - instanceId: "mq-xxx"
        instanceName: "prod-rocketmq-订单"
        topic: "order-topic"
  kafka:
    instances:
      - instanceId: "kafka-xxx"
        instanceName: "prod-kafka-日志"
        topic: "log-topic"
  lindorm:
    instances:
      - instanceId: "ld-xxx"
        instanceName: "prod-lindorm-宽表"
  elasticsearch:
    instances:
      - instanceId: "es-xxx"
        instanceName: "prod-es-日志"
  oss:
    buckets:
      - bucketName: "prod-bucket-xxx"
        instanceName: "prod-oss-附件"
```

> 仅配置生产环境实例。

#### `MiddlewareMonitorService.java` — 新增 5 个查询方法
每个方法复用 `queryLatestMetric` + 5min 缓存（同 Redis/MySQL 模式）：
- `rocketmqInstances()` → 查 `acs_mq` 堆积/生产TPS/消费TPS
- `kafkaInstances()` → 查 `acs_kafka` 堆积/生产TPS/消费TPS
- `lindormInstances()` → 查 `acs_lindorm` CPU/磁盘/QPS
- `elasticsearchInstances()` → 查 `acs_elasticsearch` CPU/磁盘/JVM内存
- `ossBuckets()` → 查 `acs_oss` 请求数/带宽/错误率

实例列表从 `@Value` 配置读取（仅生产实例）。

#### `MiddlewareController.java` — 新增 5 个接口
```java
@GetMapping("/rocketmq/instances")
@GetMapping("/kafka/instances")
@GetMapping("/lindorm/instances")
@GetMapping("/elasticsearch/instances")
@GetMapping("/oss/buckets")
```

### 前端改动

#### `src/api/middleware.js` — 新增 5 个 API 函数
```js
export function getRocketmqInstances() { return request.get('/middleware/rocketmq/instances') }
export function getKafkaInstances() { return request.get('/middleware/kafka/instances') }
export function getLindormInstances() { return request.get('/middleware/lindorm/instances') }
export function getElasticsearchInstances() { return request.get('/middleware/elasticsearch/instances') }
export function getOssBuckets() { return request.get('/middleware/oss/buckets') }
```

#### `src/components/MiddlewareDashboard.vue` — 新增 5 个 tab
```
Redis | MySQL/PolarDB | RocketMQ | Kafka | Lindorm | Elasticsearch | OSS | Pod | Node
```
每个 tab 一个 `el-table`，列按指标定（RocketMQ/Kafka：实例+堆积+生产TPS+消费TPS；Lindorm：实例+CPU+磁盘+QPS；Elasticsearch：实例+CPU+磁盘+JVM内存；OSS：Bucket+请求数+带宽+错误率）。复用现有 `metricClass` 样式分级。

---

## 二、AI 对话功能

### 方案：上下文注入 + DashScope 流式（MVP）

- **大模型**：复用已有 `dashscope-sdk-java` 2.12.0，`Generation.streamCall` 流式（qwen-turbo），不引入 langchain4j。
- **WebSocket**：新增 `spring-boot-starter-websocket`，JSR-356 `@ServerEndpoint`（jakarta.websocket）。
- **数据注入**：`AiContextBuilder` 按关键词拉真实监控数据塞 system prompt，LLM 基于真实数据回答，不编造。
- **会话历史**：内存 `ConcurrentHashMap`，30min TTL，最近 5 轮拼进 prompt。不落 DB（MVP）。
- **认证**：WS URL 带 `?token=JWT`，onOpen 用 `JwtUtil.validateToken` 校验。`AuthFilter` 放行 `/ws/ai/chat`。

### 后端改动

#### 1. `pom.xml` — 加 websocket 依赖
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

#### 2. `application.yml` — AI 配置
```yaml
app:
  ai:
    enabled: ${APP_AI_ENABLED:true}
    api-key: ${APP_AI_API_KEY:${DASHSCOPE_API_KEY:}}
    model: ${APP_AI_MODEL:qwen-turbo}
    max-tokens: ${APP_AI_MAX_TOKENS:2048}
    timeout-seconds: ${APP_AI_TIMEOUT:60}
```

#### 3. 新增 `config/AiProperties.java`
`@ConfigurationProperties("app.ai")`：enabled/apiKey/model/maxTokens/timeoutSeconds。

#### 4. 新增 `config/WebSocketConfig.java`
注册 `ServerEndpointExporter`。

#### 5. 新增 `websocket/AiChatSpringConfigurator.java`
JSR-356 端点 Spring 注入：`getEndpointInstance` → `applicationContext.getBean`。

#### 6. 新增 `websocket/AiChatEndpoint.java`（核心）
- `@ServerEndpoint(value="/ws/ai/chat", configurator=AiChatSpringConfigurator.class)`
- `onOpen`：query 取 token → `JwtUtil.validateToken` → 失败 close(1003)。存 `SessionState`。
- `onMessage`：解析 `{action}`，分发 `chat`/`abort`/`pong`。
- `chat`：调 `AiChatService.chatStream`，onNext→`sendChunk`，onComplete→`sendDone`，onError→`sendError`。
- 心跳：30s ping，90s 无 pong 关连接。

#### 7. 新增 `service/ai/AiContextBuilder.java`（核心）
按关键词拉数据，返回上下文文本（限长避免超 token）：
- 劣化/degradation → `gatewayService.degradation("day")` Top10
- 流量暴涨/surge → `gatewayService.trafficSurge("day")` Top10
- P60/耗时 → `gatewayService.p60Ranking("day")` Top10
- Redis → `middlewareMonitorService.redisInstances()`
- MySQL/数据库 → `middlewareMonitorService.mysqlInstances()`
- RocketMQ → `middlewareMonitorService.rocketmqInstances()`
- Kafka → `middlewareMonitorService.kafkaInstances()`
- Lindorm → `middlewareMonitorService.lindormInstances()`
- Elasticsearch/ES → `middlewareMonitorService.elasticsearchInstances()`
- OSS/对象存储 → `middlewareMonitorService.ossBuckets()`
- Pod → `podCpuTop()` + `podMemoryTop()`
- Node/节点 → `nodeOverview()`
- 告警/alert → `alertDataService.serviceHealth("24h")`
- 概览/overview → `gatewayService.overview("24h")`
- 无匹配 → 不注入（纯问答）

#### 8. 新增 `service/ai/AiChatService.java`（核心）
- dashscope `Generation` + `GenerationParam`，`streamCall` + `ResultCallback`。
- system prompt：监控运维 Copilot 角色 + 上下文数据 + 输出约束（中文、不编造、数据空如实说）。
- 拼历史消息（最近 5 轮）。
- 流式回调转发给传入 handler。

#### 9. `config/AuthFilter.java` — 放行 WS
`EXCLUDE_PATHS` 加 `"/ws/ai/chat"`。

### 前端改动

#### 1. 安装依赖
`npm install marked dompurify`

#### 2. 新增 `src/api/ai.js` — WS 封装
`AiChatWebSocket` 类：connect / close / chat / sendPong / onmessage 分发（chunk/done/error/ping/status）。重连 5 次指数退避。

#### 3. 新增 `src/components/AiChat.vue` — 聊天组件
- 浮动球（右下角）+ 抽屉面板（el-drawer 右侧 520px）。
- 消息列表：用户气泡（右）+ AI 气泡（左），Markdown 渲染（marked + DOMPurify），流式光标。
- 输入框 + 发送按钮（Enter 发送）。
- 连接状态指示。
- 历史上下文：发送时 `messages.slice(0,-2)` 映射成 `[{type,content}]` 作为 context。

#### 4. `src/App.vue` — 挂载
非公开页面加 `<AiChat v-if="!isPublicPage" />`。

#### 5. `vite.config.js` — WS 代理
```js
'/ws': { target: 'http://localhost:8080', ws: true, changeOrigin: true }
```

---

## 三、文件清单

### 后端新增
- `config/AiProperties.java`
- `config/WebSocketConfig.java`
- `websocket/AiChatSpringConfigurator.java`
- `websocket/AiChatEndpoint.java`
- `service/ai/AiContextBuilder.java`
- `service/ai/AiChatService.java`

### 后端修改
- `pom.xml`（加 websocket 依赖）
- `application.yml`（AI 配置 + 中间件实例配置）
- `config/AuthFilter.java`（放行 /ws/ai/chat）
- `service/MiddlewareMonitorService.java`（+5 中间件方法）
- `controller/MiddlewareController.java`（+5 接口）

### 前端新增
- `src/api/ai.js`
- `src/components/AiChat.vue`

### 前端修改
- `src/api/middleware.js`（+5 API）
- `src/components/MiddlewareDashboard.vue`（+5 tab）
- `src/App.vue`（挂载 AiChat）
- `vite.config.js`（WS 代理）
- `package.json`（marked + dompurify）

---

## 四、验证
1. 后端编译：`mvn compile -DskipTests`
2. 前端构建：`npm run build`
3. 启动后端 + 前端
4. **中间件**：打开 /middleware，切 RocketMQ/Kafka/Lindorm/Elasticsearch/OSS tab，确认表头+数据（仅生产实例）
5. **AI 对话**：点右下角浮动球 → 展开聊天 → 输入"哪些接口劣化最严重？"→ 流式中文回答含真实数据
6. 输入"Redis CPU 高不高？"→ 返回 Redis 数据
7. 输入"RocketMQ 消息堆积情况？"→ 返回 RocketMQ 数据
8. 输入"ES 集群健康吗？"→ 返回 Elasticsearch 数据
9. 无匹配关键词（如"你好"）→ 正常闲聊，不编造
10. 未登录 WS 连接被拒（1003）
