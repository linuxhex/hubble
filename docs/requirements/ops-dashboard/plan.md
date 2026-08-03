# 运维大盘(ops-dashboard)实现计划 — 后端 cloudeyes

**目标:** 补齐前端已定义的 `alert-config` / `alert-data` / `error-analysis` 三组接口,数据源基于阿里云 SLS(复用 `SlsQueryClient`),支持准实时快照与红黄绿健康度,使前端大盘接入真实数据。

**架构:** `AlertConfig`(MySQL)关联 Milvus `SlsKeyword` 模板 → `MonitorSnapshotService` 按各 config 的 `collectionInterval` + 每日时间窗定时 `countLogstore` → Caffeine 时序快照(`SnapshotCache`)→ 三组接口读快照/查 SLS 返回;健康度由 `alertThreshold` 驱动。

**技术栈:** Spring Boot 3.2.4 / Java 21 / MyBatis-Plus 3.5.12 / Caffeine(新增)/ 阿里云 SLS SDK 0.6.9 / Milvus SDK 2.3.4(已具备)

---

## 文件清单

| 文件 | 操作 | 职责 |
|---|---|---|
| `entity/AlertConfig.java` | 新增 | 监控配置实体(对应 alert_config 表) |
| `mapper/AlertConfigMapper.java` | 新增 | MyBatis-Plus mapper |
| `service/AlertConfigService.java` | 新增 | 配置 CRUD + 启停 |
| `controller/AlertConfigController.java` | 新增 | `/alert-config/query`、`/alert-config/mgmt` |
| `service/MonitorSnapshotService.java` | 新增 | 采集调度 + SLS count + 写快照 |
| `service/SnapshotCache.java` | 新增 | Caffeine 时序点存储(key=configId) |
| `service/AlertDataService.java` | 新增 | 实时统计(读快照) |
| `controller/AlertDataController.java` | 新增 | `/alert-data/query`、`/statistics` |
| `service/ErrorAnalysisService.java` | 新增 | 错误类型聚合(查 SLS 日志原文) |
| `controller/ErrorAnalysisController.java` | 新增 | `/error-analysis/query/*` |
| `service/HealthEvaluator.java` | 新增 | 红黄绿评估 |
| `config/MonitorProperties.java` | 新增 | `monitor` 配置绑定 |
| `config/CaffeineConfig.java` | 新增 | Caffeine bean |
| `vo/AlertStatisticVO.java`、`vo/ErrorTypeVO.java` 等 | 新增 | 响应 VO,字段对齐前端 |
| `pom.xml` | 修改 | 引入 `caffeine` |
| `application.yml` | 修改 | 增加 `monitor` 配置段 |

---

## `alert_config` 表字段(对齐前端 `AlertConfigManagement.vue`)

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint(PK,自增) | 主键 |
| title | varchar(100) | 监控标题(必填) |
| description | varchar(500) | 备注说明 |
| keyword_template_id | varchar(64) | 关联 Milvus `SlsKeyword.id`(取 application/logstore/keywords) |
| start_time | varchar(8) | 每日开始时间 HH:mm:ss(默认 00:00:00) |
| end_time | varchar(8) | 每日结束时间 HH:mm:ss(默认 23:59:59) |
| collection_interval | int | 采集间隔秒(10/15/30/60/300/600,默认 60) |
| alert_threshold | int | 告警阈值(默认 50) |
| alert_webhook | varchar(500) | 钉钉机器人 webhook(选填) |
| enabled | tinyint(1) | 启停 |
| deleted / created_at / updated_at | — | 通用(逻辑删除 + 自动填充) |

> 复用现有 `MetaObjectHandlerConfig` 自动填充 created_at/updated_at;逻辑删除字段 `deleted` 已全局配置。

---

## 任务拆分

### 任务 1:AlertConfig 实体 + Mapper

**目标:** 落库基础。

**文件:**
- 新增:`entity/AlertConfig.java`、`mapper/AlertConfigMapper.java`

**实现要点:**
- 实体用 `@TableName("alert_config")` + `@TableLogic` deleted;字段驼峰映射(map-underscore-to-camel-case 已开)。
- Mapper 继承 `BaseMapper<AlertConfig>`。列表查询用 LambdaQueryWrapper 支持 title 模糊 + enabled 过滤。

### 任务 2:配置与依赖

**目标:** 引入 Caffeine 与 monitor 配置。

**文件:**
- 修改:`pom.xml`(+`com.github.ben-manes.caffeine:caffeine`)
- 修改:`application.yml`(+`monitor` 段)
- 新增:`config/MonitorProperties.java`、`config/CaffeineConfig.java`

**`monitor` 配置项:** `scanIntervalSeconds`(扫描周期,默认 5)、`snapshotRetention`(每 config 保留时序点数,默认 1440)、`defaultQueryLogstore`(默认 `all`)、`errorScanLines`(错误分析拉日志行数,默认 200)。

### 任务 3:SnapshotCache + MonitorSnapshotService(采集核心)

**目标:** 按 config 采集间隔定时 SLS count,写时序快照。

**文件:**
- 新增:`service/SnapshotCache.java`、`service/MonitorSnapshotService.java`

**实现要点:**
- `SnapshotCache`:Caffeine `Cache<Long, Deque<TimePoint>>`(key=configId,TimePoint={collectedAt, logCount}),容量 = snapshotRetention,超出淘汰最旧。
- `MonitorSnapshotService`:`@Scheduled(fixedDelay = scanIntervalSeconds*1000)` 扫所有 `enabled=1` config;对「当前时间在 startTime~endTime 窗口内 && 距上次采集 ≥ collectionInterval」者执行采集。
- 采集:`keywordTemplateId` → `SlsKeywordService` 取模板(application/logstore/keywords)→ `SlsQueryClient.countLogstore(logstore, keywords, now-interval, now)` → push 进 SnapshotCache。
- 失败兜底:单个 config 采集异常记日志、保留旧快照,不影响其他 config(隔离)。

**核心逻辑示意:**
```java
// 到点判断 + 隔离采集,异常不扩散
if (inWindow(cfg) && elapsedSinceLast(cfg) >= cfg.getInterval()) {
    long count = countViaTemplate(cfg);   // 取模板→countLogstore
    snapshotCache.push(cfg.getId(), now, count);
}
```

### 任务 4:AlertConfigService + Controller(CRUD + 启停)

**目标:** 对齐 `alert.js`。

**文件:**
- 新增:`service/AlertConfigService.java`、`controller/AlertConfigController.java`

**实现要点:**
- 类级 `@RequestMapping("/alert-config")`;`/query`(GET 分页,返回 `{records,total}` 兼容前端 `records||list`)、`/query/{id}`(详情)、`/mgmt`(POST 创建)、`/mgmt/{id}`(PUT 改 / DELETE 删)、`/mgmt/{id}/enable`、`/mgmt/{id}/disable`(PUT)。
- `collectionIntervalDisplay`:在 Service 拼接展示串(如「60秒」「5分钟」)随记录返回。

### 任务 5:AlertDataService + Controller(statistics / query)

**目标:** 对齐 `getAlertStatistics` / `getAlertDataList`。

**文件:**
- 新增:`service/AlertDataService.java`、`controller/AlertDataController.java`、`vo/AlertStatisticVO.java`

**实现要点:**
- `/alert-data/query`(GET,params: alertConfigId, timeRange, current, size):从 SnapshotCache 取时序点,按 timeRange(15m/30m/6h/1d)过滤 + 分页,返回 records[{collectedAt, logCount}]。
- `/alert-data/query/statistics/{configId}`(GET,params: timeRange):从快照算 `currentLogCount`(最新点)、`todayMax`、`todayAvg`、`alertThreshold`(回显配置)、`todayAlertCount`(今日 ≥ 阈值点数)、`detailStatistics`(P0 返回基本分项结构,实现时核对前端 AlertOverview 用法补齐)。

### 任务 6:ErrorAnalysisService + Controller

**目标:** 对齐 `errorAnalysis.js`。

**文件:**
- 新增:`service/ErrorAnalysisService.java`、`controller/ErrorAnalysisController.java`、`vo/ErrorTypeVO.java`、`vo/ErrorTrendVO.java`

**实现要点:**
- `/error-analysis/query/error-types/{alertConfigId}`:取模板 keywords → `SlsQueryClient.queryLogstore` 拉最近 `errorScanLines` 行 → 内存按异常特征(异常类名/关键字)归类 Top N,返回 `[{typeName, count, percentage, growthRate, category, firstSeenAt, sampleLog}]`;growthRate 用与前一个等长窗口对比的近似值。
- `/error-analysis/query/error-types-trend/{alertConfigId}`:返回 `{timestamps, series:[{typeName, counts}]}`(基于已聚合时序)。
- `/error-analysis/query/error-trend/{alertConfigId}`、`/error-type-detail/{typeId}`:P0 实现 error-trend(错误数趋势),error-type-detail 返回样本日志明细。

### 任务 7:HealthEvaluator(红黄绿)

**目标:** 统一健康度算法,对齐前端 `getStatValueClass`。

**文件:**
- 新增:`service/HealthEvaluator.java`

**实现要点:**
- 规则:`count >= threshold` → RED;`count >= threshold * 0.5` → YELLOW;有数据且更低 → GREEN;无数据/采集失败 → GRAY。
- 提供 `evaluate(count, threshold) → Status` 枚举;AlertDataService 统计时附带每 config 的 status,供大盘 worst-wins 聚合。

### 任务 8:鉴权与路径核对

**目标:** 确保大盘接口鉴权一致、无路径冲突。

**文件:**
- 核对:`config/AuthFilter.java`(无需白名单:大盘接口默认走 JWT,与 trace/sls-keyword 一致)
- 核对:`/alert-config`、`/alert-data`、`/error-analysis` 三个前缀与现有 Controller 无冲突(现有:traces/sls-keywords/system/auth)✓

---

## 注意

- ARMS:P0/P1 不引入(前端契约无 RT/QPS;后端无 ARMS SDK)。
- 多 project(SLS 写死单 project):P0 不支持 ctp-*/device-* 专属库,P1 下钻兜底。
- `SlsQueryClient` 要求**秒级**时间戳,采集窗口与 timeRange 换算注意单位。

## 审查补充(阶段 3 微调)

- **timeRange 解析**:统一工具 `TimeRanges.toSeconds("15m"/"30m"/"6h"/"1d")` → 秒,alert-data 与 error-analysis 共用。
- **采集并发**:到点的多个 config 采集任务提交到现有 `queryExecutor`(core4/max8)并行,天然限流,避免同时打爆 SLS。
- **取模板方法**:实现任务 3 前核对 `SlsKeywordService` 是否能按 `keywordTemplateId` 取到 `SlsKeyword`(application/logstore/keywords);若仅有分页查询,补一个 `getById` 走 Milvus。
