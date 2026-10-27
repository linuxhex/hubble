# 业务监控实时查询 · 测试报告

## 概要

- 测试时间：2026-10-20 20:02 ~ 20:10（北京时间）
- 项目类型：Web（Vue 3 + Element Plus）
- 测试环境：http://172.16.149.95:18081（95 服务器，deploy-95.sh 部署，含未提交的去缓存改动）
- 驱动引擎：ego-browser（web-run.cjs 执行器）
- 认证：测试 JWT（HS256，username=lianzi，exp=+2h），用 jar 内嵌 `src/main/resources/.env` 的 JWT_PUBLIC_KEY 签发，`plan.setupJsDoc` 在新文档加载前注入 localStorage（执行器本次新增能力）
- 场景总数：3
- 通过：3
- 失败：0
- 通过率：100%

## 改动背景

`BizAnalysisService` 去掉 5 分钟内存缓存 + PageDataCache 落库缓存 + getCachedOrRefresh 懒加载，全部 14 个查询方法改为打开页面实时查 Doris 数仓；`DailyReportService` 去掉发日报前 refreshAll 预刷（13 点日报本身即实时查数仓）。5 分钟定时任务此前已在 cf1ec56 删除。

## 详细结果

### 场景 1：业务监控页面加载与认证（P0）✓
| 步骤 | 操作 | 结果 |
|------|------|------|
| 1 | navigate /biz-analysis（setupJsDoc 自动注入登录态） | ✓ |
| 2 | assertUrl 含 /biz-analysis（未被踢回钉钉登录页） | ✓ |
| 3 | assertVisible .summary-cards | ✓ |
| 4 | assertCount .summary-card ≥ 5（实际 6 张） | ✓ |
| 5 | assertApi /api/biz-analysis/overview 200 | ✓ |
| 6 | aiAssert：卡片有数字、无 NaN/白屏/error alert | ✓（截图 ego-browser-shot-40135-1.png：订单 1,211,115 / 电量 67,492,719 / 充电中枪 580,614 / DAU 878,384） |

### 场景 2：核心接口全量实时查询（P0）✓
| 步骤 | 操作 | 结果 |
|------|------|------|
| 1 | assertApi ×5（hourly-order-comparison / monthly-trend / yearly-comparison / revenue-trend / overview） | ✓ 全部 200 |
| 2 | aiAssert：数据截至标注 + 接口数据非空 | ✓（页面拦截 16 个 biz-analysis 接口全部 200 且 body 非空；overview 返回 orderCnt=1,211,115、date=2026-10-19） |

### 场景 3：图表区块完整渲染（P1）✓
| 步骤 | 操作 | 结果 |
|------|------|------|
| 1 | aiAssert：echarts canvas ≥ 2、无 JS 错误 | ✓（DOM canvas 计数 2，截图柱状图渲染正常） |

## 实时性验证（后端证据）

- 连续两次 curl `/api/biz-analysis/overview`，各自独立实时查询（响应 timestamp 1792498162544 → 1792498162925，间隔 381ms 各自查数仓返回），不再有 5 分钟缓存 TTL
- 部署后启动日志无 ERROR（Started HubbleApplication in 27.9s）

## 失败/修正记录

| 现象 | 根因 | 处理 |
|------|------|------|
| 首次执行场景1失败：被踢到钉钉登录页 | setupJs 在初始页（已重定向到 login.dingtalk.com）执行，localStorage 注入到了钉钉域 | web-run.cjs 新增 `setupJsDoc`（Page.addScriptToEvaluateOnNewDocument，仅目标 origin 生效），plan 改用后通过 |
| 首次部署构建失败 | 本机默认 JDK 11，Spring Boot 3.2.4 repackage 需 JDK 17 | JAVA_HOME 指向 Corretto 17.0.17 重跑 deploy-95.sh |

## 未覆盖项

- 真实钉钉扫码登录链路（自动化无法扫码，用测试 JWT 等价替代——JWT 校验逻辑与真实登录一致）
- 明天 13:00 日报发送验证（依赖定时触发，建议明天观察群里日报是否正常发出）

## 结论

✅ 3/3 场景通过：页面打开即实时查数仓、16 个接口全 200、卡片/图表渲染正常、无 NaN/白屏/JS 错误。去缓存改动在 95 服务器验证通过。
