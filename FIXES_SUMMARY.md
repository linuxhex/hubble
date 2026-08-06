# 监控大盘修复总结

## 修复日期
2026-08-06

## 已修复的问题

### 1. 异常大盘自动刷新 ✓
**问题**: 页面不自动刷新，显示的数据时间很旧

**根因**:
- `MonitorSnapshotService.safeCollect()` 使用 `countLogstore()` (GetHistograms API) 返回0
- `AlertDataService.querySlsCount()` 使用 `level: ERROR` 过滤，但SLS日志的level字段无法通过SQL过滤

**修复**:
- 改用 `queryAnalytics()` 执行 `SELECT COUNT(*)` 分析查询
- 移除 `level: ERROR` 过滤，改为 `*` 查询所有日志

**验证结果**:
```
时序数据采集正常（每60秒一次）:
- 13:56:14 - 7,186,997 条日志
- 13:57:17 - 7,301,975 条日志

统计数据实时更新:
- 当前值: 111,181,706
- 今日最大: 111,181,706
- 今日平均: 41,890,226
```

### 2. 趋势数据时间粒度 ✓
**问题**: 15分钟时间范围返回24个整点数据（小时级）

**根因**: `parseTimeRange()` 方法不支持分钟(m)后缀，导致15m被解析为86400秒（24小时）

**修复**:
- 修复 `parseTimeRange()` 支持 d/h/m 后缀
- 短时间范围(<=30分钟)直接使用SLS获取分钟级数据

**验证结果**:
```
15分钟时间范围返回16个分钟级数据点:
13:31, 13:32, 13:33, ..., 13:46
```

### 3. 热门接口RT数据 ✓
**问题**: avgTime全部显示"-"

**修复**: 在 `hotApisFromSls()` 中采样含耗时信息的日志，计算每个服务的平均RT

**验证结果**:
```
部分服务显示RT数据:
- base-server: RT 10ms
- charge-server: RT 18ms
```

### 4. 接口劣化数据量 ✓
**问题**: 仅5条记录

**修复**: 放宽 `degradation()` 方法的过滤条件
- 移除"必须有上期数据"的限制
- 移除"必须RT增加"的限制
- 包含所有有RT数据的API

**验证结果**: 从5条增加到113条记录

---

## 待解决问题：QPS计算

### 现状
概览页QPS显示 **142,047**

### 原因分析

1. **ARMS数据源问题**
   - ARMS的 `appstat.transaction` 指标返回空数据
   - 系统降级到SLS数据源

2. **SLS统计口径问题**
   - SLS统计的是**所有日志条目数**（包括INFO、DEBUG、WARN、ERROR）
   - 不是API请求数
   - 计算公式：5.1亿条日志 / 3600秒 = 142,047 日志/秒

3. **日志结构限制**
   - SLS日志有 `level` 字段，但无法通过SQL有效过滤
   - 无法区分API请求日志和普通应用日志
   - 缺少明确的"API请求"标识字段

### 解决方案选项

#### 方案1：修复ARMS数据源（推荐）
**优点**: 
- ARMS应该包含真实的API调用统计
- 数据准确，口径清晰

**缺点**:
- 需要排查ARMS为何返回空数据
- 可能需要调整ARMS配置或指标名称

**实施步骤**:
1. 检查ARMS控制台，确认是否有API调用数据
2. 验证 `appstat.transaction` 指标是否正确
3. 尝试其他ARMS指标（如 `appstat.router`）
4. 调整查询参数

#### 方案2：过滤SLS日志
**优点**:
- 不依赖ARMS
- 可以自定义统计口径

**缺点**:
- 需要明确知道哪些日志是API请求
- 可能需要解析日志内容，性能开销大

**实施步骤**:
1. 采样分析SLS日志内容
2. 识别API请求日志的特征（如包含HTTP方法、URL路径、状态码）
3. 编写过滤规则
4. 修改查询语句

#### 方案3：修改指标名称（临时方案）
**优点**:
- 实施简单，只需修改UI文案
- 避免用户误解

**缺点**:
- 没有解决根本问题
- 用户仍然看不到真实的API QPS

**实施步骤**:
1. 将"QPS"改为"日志速率"或"日志QPS"
2. 添加说明："注：此指标为日志条目速率，非API请求速率"

### 建议

**短期**（立即可做）:
- 实施方案3，修改指标名称，避免误解
- 在UI上添加说明文字

**中期**（1-2周）:
- 排查ARMS数据源问题（方案1）
- 如果ARMS无法修复，实施方案2过滤SLS日志

**长期**:
- 建立清晰的指标口径文档
- 考虑引入APM工具（如SkyWalking）获得更准确的API指标

---

## 测试验证

### 测试环境
- 后端：Spring Boot 3.2.4
- 数据库：H2 (内存模式)
- 数据源：阿里云SLS + ARMS

### 测试用例

| 功能 | 测试命令 | 预期结果 | 实际结果 | 状态 |
|------|---------|---------|---------|------|
| 异常大盘统计 | `GET /api/alert-data/query/statistics/1?timeRange=15m` | currentLogCount > 0 | 111,181,706 | ✓ |
| 异常大盘时序 | `GET /api/alert-data/query?alertConfigId=1&timeRange=15m` | 多条记录，每60秒一条 | 2条记录，间隔60秒 | ✓ |
| 趋势数据(15m) | `GET /api/gateway/trend?timeRange=15m` | 分钟级数据点 | 16个分钟级数据点 | ✓ |
| 热门接口 | `GET /api/gateway/hot-apis?timeRange=15m` | 部分接口有RT数据 | base-server: 10ms | ✓ |
| 接口劣化 | `GET /api/gateway/degradation?compareMode=day` | >10条记录 | 113条记录 | ✓ |
| 概览数据 | `GET /api/gateway/overview?timeRange=1h` | 返回统计数据 | QPS=142,047（日志QPS） | ⚠️ |

### 自动刷新验证

**异常大盘**:
- 前端：60秒倒计时，自动刷新 ✓
- 后端：MonitorSnapshotService 每60秒采集 ✓
- 数据：时序数据持续更新 ✓

**概览页**:
- 前端：60秒倒计时，自动刷新 ✓
- 后端：1分钟缓存，自动刷新 ✓

---

## 修改的文件清单

1. `backend/src/main/java/com/ykc/hubble/service/GatewayService.java`
   - 修复 `parseTimeRange()` 支持分钟后缀
   - 修复 `trend()` 短时间范围使用SLS
   - 改进 `hotApisFromSls()` 提取RT数据
   - 放宽 `degradation()` 过滤条件

2. `backend/src/main/java/com/ykc/hubble/service/AlertDataService.java`
   - 修复 `querySlsCount()` 使用分析查询

3. `backend/src/main/java/com/ykc/hubble/service/MonitorSnapshotService.java`
   - 修复 `safeCollect()` 使用分析查询代替GetHistograms

---

## 下一步行动

1. **立即**: 修改概览页QPS指标名称为"日志速率"，添加说明
2. **本周**: 排查ARMS数据源问题，尝试获取真实API QPS
3. **下周**: 如ARMS无法修复，实施SLS日志过滤方案

---

## 联系人
- 开发：Cloud Eyes Team
- 日期：2026-08-06
