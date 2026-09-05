# 经营分析页面 — 实现计划

## Context
做一个经营分析独立页面，展示收入/订单/电量的增长趋势与环比、小程序活跃数据、桩总量与充电中桩数、每日订单电量、各业务场景（直连/互联/流量方）拆分。数据来自数仓 Doris（cwork-data 已验证可取到）。**此页面仅 lianzi 用户可见**，其他用户看不到菜单也进不了页面。

## 数据来源（cwork-data 已验证）

| 指标 | 数仓表（internal.ads） | 关键字段 | 验证结果 |
|------|----------------------|---------|---------|
| 日订单/电量/收入 | `ads_station_daily_operation_dt` | `order> cnt` `charged_power` `income`（脱敏***） | ✓ 到 2026-09-04 |
| 月环比/增速 | 同上按月 GROUP BY | `SUM(order_cnt)` `SUM(charged_power)` | ✓ 2026-01～09 |
| 业务场景拆分 | `ads_order_history_agg_dt_da` | `trade_mode_type`(1/2/3) `record_num` `charged_power` | ✓ |
| 零售/非零售/互联/新电途 | `ads_station_daily_operation_dt` | `order_cnt_retail` `order_cnt_non_retail` `order_cnt_twjs` `order_cnt_xdt` + `power_*` | ✓ |
| 小程序 DAU/点击 | `ads_omp_point_ad_dau_click_di` | `dau_user_cnt` `ad_click_user_cnt` | ✓ 9-4 DAU 885604 |
| 小程序 PV/UV | `ads_user_source_pvuv_dt` | `dau` `dau_pv` `order_uv` `order_pv` | ✓ |
| 充电最活跃用户排名 | `ads_recharge_user_behavir_ord_anal_dt` | `user_id` `total_ord_cnt` `total_price` | ✓ |
| 桩总量/在充电桩 | `ads_gun_status_dt_da_distributed` | `gun_status`(0空闲/2充电中/3故障) `COUNT(*)` | ✓ |

> 收入字段被 query-server 脱敏返回 `***`。页面展示收入时用脱敏值标注"脱敏"，或用订单量+电量作为主要经营指标。

## 权限控制：仅 lianzi 可见

### 方案
- **后端**：经营分析接口加权限校验，从 JWT claims 取 `username`/`phone`，校验是否为 `lianzi`（nickname 或 phone 匹配），非 lianzi 返回 403。
- **配置化**：允许的用户列表放 `application.yml`（`biz-analysis.allowed-users: lianzi`），不硬编码。
- **前端**：菜单项根据当前用户 nickname 动态显示（`v-if="authStore.user?.nickname === 'lianzi'"`），路由守卫也校验。
- **AuthFilter**：经营分析接口路径 `/biz-analysis/` 加入白名单（无需登录中间件校验，由业务层自己校验 lianzi），或不放白名单走正常 JWT 校验 + 业务层 lianzi 校验（更安全，选后者）。

## 后端改动

### 1. `application.yml` — 经营分析配置
```yaml
biz-analysis:
  allowed-users: ${BIZ_ANALYSIS_USERS:lianzi}
  # 数仓 query-server 地址（复用 cwork-data 的配置）
  query-server-url: ${MCP_BASE_URL:http://query-server-internal:8080}
  query-user: ${MCP_USER:}
  query-pass: ${MCP_PASS:}
```

### 2. 新增 `config/BizAnalysisProperties.java`
`@ConfigurationProperties("biz-analysis")`：allowedUsers（List<String>）、queryServerUrl、queryUser、queryPass。

### 3. 新增 `client/DorisQueryClient.java` — 数仓查询客户端
HTTP 直连 query-server（同 cwork-data 的 mcp_client.py 协议），发只读 SQL，返回 JSON 结果。
- `query(String sql) → List<Map<String,Object>>`（带前置校验：分区过滤、只读）
- 复用 cwork-data 的 query-server 地址和凭证

### 4. 新增 `service/BizAnalysisService.java` — 经营分析核心
每个方法构造 SQL → 调 `DorisQueryClient.query` → 返回结构化数据：
- `dailyOverview()` — 今日订单量/电量/桩总数/充电中桩数/DAU/点击数（汇总卡片）
- `monthlyTrend()` — 近 12 月订单量/电量 + 环比/增速（趋势图）
- `dailyOrderEnergy(days)` — 近 N 日每日订单量/电量（折线图）
- `scenarioBreakdown()` — 业务场景拆分（trade_mode_type 1/2/3 + 零售/非零售/互联/新电途）
- `activeUsersTop(limit)` — 充电最活跃用户排名（按 total_ord_cnt DESC）
- `appActive()` — 小程序 DAU/点击/PV/UV 近 N 日趋势

### 5. 新增 `controller/BizAnalysisController.java`
```java
@RestController
@RequestMapping("/biz-analysis")
public class BizAnalysisController {
    @GetMapping("/overview")      // 汇总卡片
    @GetMapping("/monthly-trend") // 月度趋势+环比
    @GetMapping("/daily")         // 每日订单电量
    @GetMapping("/scenario")      // 业务场景拆分
    @GetMapping("/active-users")  // 活跃用户排名
    @GetMapping("/app-active")    // 小程序活跃数据
}
```
每个接口先校验当前用户是否 lianzi（从 SecurityContext/JWT 取 username，匹配 allowedUsers），非 lianzi 返回 403。

### 6. lianzi 权限校验
在 Controller 或 Service 入口校验：
```java
private void checkLianzi() {
    String username = jwtUtil.getUsername(tokenFromRequest);
    if (!bizAnalysisProperties.getAllowedUsers().contains(username)) {
        throw new BusinessException(403, "无权限访问经营分析");
    }
}
```
token 从请求头 `Authorization: Bearer xxx` 取（这些接口走正常 JWT 校验，不放 AuthFilter 白名单）。

## 前端改动

### 1. 新增 `src/api/biz-analysis.js`
```js
export function getOverview() { return request.get('/biz-analysis/overview') }
export function getMonthlyTrend() { return request.get('/biz-analysis/monthly-trend') }
export function getDaily() { return request.get('/biz-analysis/daily') }
export function getScenario() { return request.get('/biz-analysis/scenario') }
export function getActiveUsersTop() { return request.get('/biz-analysis/active-users') }
export function getAppActive() { return request.get('/biz-analysis/app-active') }
```

### 2. 新增 `src/components/BizAnalysisDashboard.vue` — 经营分析页面
布局（自上而下）：
1. **汇总卡片**：今日订单量 / 今日电量 / 桩总数 / 充电中桩数 / 小程序 DAU / 广告点击数
2. **月度趋势**（echarts 折线+柱状）：近 12 月订单量+电量，标注环比/增速
3. **每日订单电量**（echarts 折线）：近 30 日每日订单量9+电量双轴
4. **业务场景拆分**（el-table + echarts 饼图）：
   - trade_mode_type 1/2/3（直连/互联A/互联B）订单量+电量
   - 零售/非零售/互联/新电途 订单量+电量
5. **小程序活跃趋势**（echarts 折线）：近 30 日 DAU + 点击数
6. **充电最活跃用户排名**（el-table）：user_id / 订单数 / 总金额，Top20

### 3. `src/router/index.js` — 新增路由
```js
{
  path: '/biz-analysis',
  name: 'BizAnalysis',
  component: BizAnalysisDashboard
}
```

### 4. `src/App.vue` — 菜单项（仅 lianzi 可见）
```vue
<el-menu-item index="/biz-analysis" v-if="authStore.user?.nickname === 'lianzi'">
  <el-icon><TrendCharts /></el-icon>
  <span>经营分析</span>
</el-menu-item>
```

### 5. 路由守卫（`src/router/index.js`）
```js
router< .beforeEach((to, from, next) => {
  if (to.path === '/biz-analysis') {
    const user = JSON.parse(localStorage.getItem('auth_user') || '{}')
    if (user.nickname !== 'lianzi') { next('/unauthorized'); return }
  }
  // ...现有守卫
})
```

## 文件清单

### 后端新增
- `config/BizAnalysisProperties.java`
- `client/DorisQueryClient.java`
- `service/BizAnalysisService.java`
- `controller/BizAnalysisController.java`

### 后端修改
- `application.yml`（biz-analysis 配置）

### 前端新增
- `src/api/biz-analysis.js`
- `src/components/BizAnalysisDashboard.vue`

### 前端修改
- `src/router/index.js`（+路由+守卫）
- `src/App.vue`（+菜单项 v-if lianzi）

## 验证
1. 后端编译：`mvn compile -DskipTests`
2. 用 lianzi 用户登录 → 菜单出现"经营分析" → 进入页面
3. 汇总卡片显示今日订单/电量/桩数/DAU
4. 月度趋势图显示近 12 月数据 + 环比
5. 业务场景拆分显示直连/互联/流量方订单量+电量
6. 活跃用户排名显示 Top20
7. 用非 lianzi 用户登录 → 菜单无"经营分析" → 直接访问 /biz-analysis 跳转 /unauthorized
8. 后端接口非 lianzi 调用返回 403
