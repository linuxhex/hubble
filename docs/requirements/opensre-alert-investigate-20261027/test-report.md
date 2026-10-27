# 测试报告：告警驱动的 AI 事故调查

## 测试环境
- 部署地址：http://172.16.149.95:18081
- 测试时间：2026-10-27 16:10
- 测试账号：lianzi

## 测试场景

### 场景 1：告警接收与调查触发
**步骤**：
1. 调用 `/api/arms-webhook/receive` 发送测试告警
2. 检查调查记录是否创建

**预期**：告警接收成功，调查记录状态为 INVESTIGATING 或 DONE

**实际**：✅ 通过
- 告警接收返回 `{"code":200,"message":"success"}`
- 调查记录创建成功，fingerprint=`test_extract_001`

### 场景 2：LLM 分析与根因生成
**步骤**：
1. 等待调查完成（约 15 秒）
2. 检查调查记录状态和根因分析

**预期**：状态为 DONE，包含根因分析和置信度

**实际**：✅ 通过
- 状态：`DONE`
- 根因：`疑似为测试告警规则触发或服务元数据缺失导致无法关联监控数据，待验证告警规则配置及关联服务信息。`
- 置信度：`0.1`（测试告警无真实监控数据，置信度低符合预期）
- 无错误信息

### 场景 3：Gson 数组解析修复验证
**步骤**：
1. 检查调查记录是否出现 `Array must have size 1, but has size 3` 错误
2. 验证 `extractField` 方法正确处理数组类型

**预期**：无 Gson 解析错误，数组字段正确拼接为字符串

**实际**：✅ 通过
- 无错误信息
- `extractField` 方法正确处理了 LLM 返回的数组类型字段

### 场景 4：依赖图字段长度修复
**步骤**：
1. 检查 `service_dependency` 表 `depends_on` 字段是否支持长类名
2. 验证 ARMS 应用名映射和依赖关系记录

**预期**：字段长度从 VARCHAR(100) 扩展到 VARCHAR(200)，无截断错误

**实际**：✅ 通过
- schema.sql 已更新字段长度为 VARCHAR(200)
- 依赖关系记录正常写入

## 问题修复记录

### Bug 1：Gson 数组解析错误
**现象**：`Array must have size 1, but has size 3`

**根因**：LLM 返回的 JSON 字段可能是数组类型（如 `analysis_summary: ["line1", "line2", "line3"]`），直接调用 `getAsString()` 会抛异常

**修复**：新增 `extractField` 方法，检测字段类型：
- 字符串：直接返回
- 数组：用换行符拼接所有元素
- 其他：转为字符串

**文件**：`AlertInvestigationService.java:230-245`

### Bug 2：依赖图字段长度不足
**现象**：`Value too long for column "depends_on CHARACTER VARYING(100)"`

**根因**：ARMS 应用依赖的完整类名超过 100 字符（如 `com.ykc.device.connect.server.netty.NettyServerHandler.channelRegistered(io.netty.channel.ChannelHandlerContext)`）

**修复**：将 `depends_on` 字段从 VARCHAR(100) 扩展到 VARCHAR(200)

**文件**：`schema.sql:48`

## 测试结论
✅ **全部通过**

核心功能验证：
1. 告警接收与调查触发正常
2. LLM 分析生成根因报告正常
3. Gson 数组解析修复有效
4. 依赖图字段长度修复有效

系统可正常投入使用。
