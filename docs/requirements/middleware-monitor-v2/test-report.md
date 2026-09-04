# 测试报告

## 概要
- 测试时间：2026-09-04 21:30
- 项目类型：Web
- 测试环境：http://localhost:5173（前端）+ http://localhost:8080（后端）
- 驱动引擎：ego-browser
- 场景总数：4
- 通过：4
- 失败：0
- 通过率：100%

## 改动范围

| 文件 | 改动内容 |
|------|---------|
| GrafanaClient.java（新增） | Grafana Prometheus 查询客户端，支持多数据源 |
| MiddlewareMonitorService.java | 新增 podCpuTop/podMemoryTop/nodeOverview |
| MiddlewareController.java | 新增 3 个 API 端点 |
| application.yml | 新增 grafana 配置（ds-uid + node-ds-uid） |
| middleware.js | 新增 3 个前端 API 调用 |
| MiddlewareDashboard.vue | 新增 Pod/Node Tab，两栏布局 |
| GatewayService.java | 告警防抖 3h→24h |
| MonitorSnapshotService.java | 红盘告警防抖 3h→24h |

## 修复的问题

1. **GrafanaClient 越界 bug**：Node 查询返回空 fields 时 `Index -1 out of bounds`，增加空数组保护
2. **Node 数据源错误**：自建 Prometheus 无 node-exporter 指标，改用 ARMS 数据源（`cem0jt0mij668b`）
3. **Node 节点重复**：内存查询返回重复 instance，用 Map 去重

## 详细结果

### 场景 1：中间件大盘页面加载 ✓
| 步骤 | 操作 | 结果 |
|------|------|------|
| 1 | 导航到 /middleware | ✓ |
| 2 | 验证 4 个 Tab 存在 | ✓ Redis/MySQL/Pod/Node |

### 场景 2：Pod 监控（真实数据）✓
| 步骤 | 操作 | 结果 |
|------|------|------|
| 1 | 切换到 Pod Tab | ✓ |
| 2 | Pod CPU Top10 展示 | ✓ charge-server CPU:1.2 |
| 3 | Pod 内存 Top10 展示 | ✓ order-mos MEM:14218MB |

截图：screenshots/ego-browser-shot-13478-2.png

### 场景 3：Node 监控（真实数据）✓
| 步骤 | 操作 | 结果 |
|------|------|------|
| 1 | 切换到 Node Tab | ✓ |
| 2 | 等待数据加载（11 秒） | ✓ |
| 3 | 节点列表展示 | ✓ 41 个节点 |
| 4 | CPU%/内存% 数值正确 | ✓ 172.16.201.31 CPU:40.4% MEM:77.4% |

截图：screenshots/ego-browser-shot-13478-1.png

### 场景 4：Redis 回归验证 ✓
| 步骤 | 操作 | 结果 |
|------|------|------|
| 1 | 切换回 Redis Tab | ✓ |
| 2 | 实例列表展示 | ✓ 31 个实例 |
| 3 | 表格列完整 | ✓ 实例名/规格/状态/CPU%/内存%/QPS |

## 性能问题

| 接口 | 响应时间 | 说明 |
|------|---------|------|
| /middleware/node/overview | ~11s | Grafana ARMS 查询慢 |
| /middleware/pod/cpu | ~2s | 可接受 |
| /middleware/pod/memory | ~2s | 可接受 |
| /middleware/redis/instances | ~1s | 正常 |

**建议**：Node 接口需要加缓存，避免每次请求都查 Grafana。

## 结论

全部 4 个场景通过，新增的 Pod/Node 监控功能正常，数据展示正确。Node 接口响应慢，建议后续加缓存优化。
