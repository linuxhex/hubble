<template>
  <div class="biz-analysis-page">
    <div class="page-header">
      <h2>业务监控</h2>
      <el-button size="small" @click="fetchAll" :loading="loading">刷新</el-button>
    </div>

    <!-- 1. 汇总卡片 -->
    <div class="summary-cards" v-loading="overviewLoading" element-loading-text="概览数据加载中...">
      <div class="summary-card">
        <div class="card-label">累计订单量</div>
        <div class="card-value">{{ formatNum(overview.orderCnt) }}</div>
      </div>
      <div class="summary-card">
        <div class="card-label">累计电量(kWh)</div>
        <div class="card-value">{{ formatNum(overview.chargedPower) }}</div>
      </div>
      <div class="summary-card">
        <div class="card-label">枪总量</div>
        <div class="card-value">{{ formatNum(overview.totalGuns) }}</div>
      </div>
      <div class="summary-card">
        <div class="card-label">充电中枪数</div>
        <div class="card-value highlight">{{ formatNum(overview.chargingGuns) }}</div>
      </div>
      <div class="summary-card">
        <div class="card-label">小程序 DAU</div>
        <div class="card-value">{{ formatNum(overview.dau) }}</div>
      </div>
      <div class="summary-card">
        <div class="card-label">广告点击数</div>
        <div class="card-value">{{ formatNum(overview.adClick) }}</div>
      </div>
    </div>
    <el-alert v-if="overviewFailed" type="error" :closable="false" style="margin-bottom: 12px">
      <template #title>
        概览数据加载失败
        <el-link type="primary" :underline="false" style="margin-left: 8px" @click="loadOverview">重试</el-link>
      </template>
    </el-alert>

    <!-- 1.5 今日 vs 昨日 小时订单对比 -->
    <div class="chart-section" v-if="hourlyCompData.hours && hourlyCompData.hours.length > 0">
      <div class="section-title">
        今日 vs 昨日 小时订单实时对比（{{ hourlyCompData.todayDate || '-' }} vs {{ hourlyCompData.yesterdayDate || '-' }}）
        <span v-if="hourlyCompData.alertCount > 0" class="yoy-badge down" style="margin-left:12px">
          {{ hourlyCompData.alertCount }} 个时段落后 50%+
        </span>
      </div>
      <div ref="hourlyCompChartRef" class="chart-container"></div>
    </div>

    <!-- 1.6 今日 vs 昨日 小时充电中订单 -->
    <div class="chart-section" v-if="hourlyChargingCompData.hours && hourlyChargingCompData.hours.length > 0">
      <div class="section-title">
        小时充电中订单对比（{{ hourlyChargingCompData.todayDate }} vs {{ hourlyChargingCompData.yesterdayDate }}）
        <span v-if="hourlyChargingCompData.alertCount > 0" class="alert-badge">⚠ {{ hourlyChargingCompData.alertCount }}个时段差异≥10000</span>
      </div>
      <div ref="hourlyChargingCompChartRef" class="chart-container"></div>
    </div>

    <!-- 2. 趋势区：月度趋势 + 年度同比 并排 -->
    <div class="chart-row two-col" v-if="monthlyData.length > 0 || (yearlyData.comparison && yearlyData.comparison.length > 0)">
      <div class="chart-section" v-if="monthlyData.length > 0">
        <div class="section-title">月度趋势（近 12 月）</div>
        <div ref="monthlyChartRef" class="chart-container"></div>
      </div>
      <div class="chart-section" v-if="yearlyData.comparison && yearlyData.comparison.length > 0">
        <div class="section-title">
          年度同比（{{ yearlyData.currentYear || '-' }} vs {{ yearlyData.lastYear || '-' }}）
          <span v-if="yearlyData.orderYoy != null" class="yoy-badge" :class="yearlyData.orderYoy >= 0 ? 'up' : 'down'">
            订单 {{ yearlyData.orderYoy >= 0 ? '+' : '' }}{{ yearlyData.orderYoy }}%
          </span>
          <span v-if="yearlyData.powerYoy != null" class="yoy-badge" :class="yearlyData.powerYoy >= 0 ? 'up' : 'down'" style="margin-left:8px">
            电量 {{ yearlyData.powerYoy >= 0 ? '+' : '' }}{{ yearlyData.powerYoy }}%
          </span>
        </div>
        <div ref="yearlyChartRef" class="chart-container"></div>
      </div>
    </div>

    <!-- 3. 趋势区：每日订单电量 + 收入趋势 并排 -->
    <div class="chart-row two-col" v-if="dailyData.length > 0 || revenueData.length > 0">
      <div class="chart-section" v-if="dailyData.length > 0">
        <div class="section-title">每日订单 & 电量（近 30 日）</div>
        <div ref="dailyChartRef" class="chart-container"></div>
      </div>
      <div class="chart-section" v-if="revenueData.length > 0">
        <div class="section-title">收入趋势 & 客单价（近 30 日）</div>
        <div ref="revenueChartRef" class="chart-container"></div>
      </div>
    </div>

    <!-- 4. 趋势区：枪利用率 + 充电时段分布 并排 -->
    <div class="chart-row two-col" v-if="utilizationData.length > 0 || hourlyData.length > 0">
      <div class="chart-section" v-if="utilizationData.length > 0">
        <div class="section-title">枪利用率趋势（近 30 日）</div>
        <div ref="utilizationChartRef" class="chart-container"></div>
      </div>
      <div class="chart-section" v-if="hourlyData.length > 0">
        <div class="section-title">充电时段分布（近 7 日）</div>
        <div ref="hourlyChartRef" class="chart-container"></div>
      </div>
    </div>

    <!-- 5. 用户区：DAU + MAU 并排 -->
    <div class="chart-row two-col" v-if="appActiveData.length > 0 || mauData.length > 0">
      <div class="chart-section" v-if="appActiveData.length > 0">
        <div class="section-title">DAU 日活趋势（近 30 日）</div>
        <div ref="appActiveChartRef" class="chart-container"></div>
      </div>
      <div class="chart-section" v-if="mauData.length > 0">
        <div class="section-title">月均 DAU 趋势（近 6 月）</div>
        <div ref="mauChartRef" class="chart-container"></div>
      </div>
    </div>

    <!-- 6. 场景拆分 -->
    <div class="scenario-section" v-if="(scenario.byTradeMode && scenario.byTradeMode.length > 0) || channelTableData.length > 0">
      <div class="section-title">业务场景拆分</div>
      <div class="scenario-grid">
        <div class="scenario-card">
          <div class="sub-title">按交易模式</div>
          <el-table :data="scenario.byTradeMode || []" stripe border size="small">
            <el-table-column label="模式" width="120">
              <template #default="{ row }">{{ tradeModeLabel(row.tradeMode) }}</template>
            </el-table-column>
            <el-table-column label="订单量" width="120" align="right">
              <template #default="{ row }">{{ formatNum(row.orderCnt) }}</template>
            </el-table-column>
            <el-table-column label="电量(kWh)" align="right">
              <template #default="{ row }">{{ formatNum(row.chargedPower) }}</template>
            </el-table-column>
          </el-table>
        </div>
        <div class="scenario-card">
          <div class="sub-title">按渠道（昨日）</div>
          <el-table :data="channelTableData" stripe border size="small">
            <el-table-column label="渠道" prop="channel" width="120" />
            <el-table-column label="订单量" prop="order" width="120" align="right" />
            <el-table-column label="电量(kWh)" prop="power" align="right" />
          </el-table>
        </div>
      </div>
    </div>

    <!-- 7. 排名区：区域分布 + 站点排名 + 活跃用户 三列 -->
    <div class="chart-row three-col" v-if="regionData.length > 0 || stationData.length > 0 || activeUsers.length > 0">
      <div class="ranking-section" v-if="regionData.length > 0">
        <div class="section-title">区域分布 Top20</div>
        <el-table :data="regionData" stripe border size="small" style="width: 100%" max-height="500">
          <el-table-column label="#" width="50" align="center">
            <template #default="{ $index }">{{ $index + 1 }}</template>
          </el-table-column>
          <el-table-column label="城市" min-width="100" prop="region" />
          <el-table-column label="订单量" width="100" align="right">
            <template #default="{ row }">{{ formatNum(row.orderCnt) }}</template>
          </el-table-column>
          <el-table-column label="电量(kWh)" width="110" align="right">
            <template #default="{ row }">{{ formatNum(row.chargedPower) }}</template>
          </el-table-column>
        </el-table>
      </div>
      <div class="ranking-section" v-if="stationData.length > 0">
        <div class="section-title">站点排名 Top20</div>
        <el-table :data="stationData" stripe border size="small" style="width: 100%" max-height="500">
          <el-table-column label="#" width="50" align="center">
            <template #default="{ $index }">{{ $index + 1 }}</template>
          </el-table-column>
          <el-table-column label="站点" min-width="120" show-overflow-tooltip prop="stationName" />
          <el-table-column label="订单量" width="100" align="right">
            <template #default="{ row }">{{ formatNum(row.orderCnt) }}</template>
          </el-table-column>
          <el-table-column label="电量(kWh)" width="110" align="right">
            <template #default="{ row }">{{ formatNum(row.chargedPower) }}</template>
          </el-table-column>
        </el-table>
      </div>
      <div class="ranking-section" v-if="activeUsers.length > 0">
        <div class="section-title">活跃用户 Top20</div>
        <el-table :data="activeUsers" stripe border size="small" style="width: 100%" max-height="500">
          <el-table-column label="#" width="50" align="center">
            <template #default="{ $index }">{{ $index + 1 }}</template>
          </el-table-column>
          <el-table-column label="用户ID" min-width="120" show-overflow-tooltip prop="userId" />
          <el-table-column label="订单数" width="90" align="right">
            <template #default="{ row }">{{ formatNum(row.orderCnt) }}</template>
          </el-table-column>
          <el-table-column label="总金额" width="100" align="right">
            <template #default="{ row }">{{ row.totalPrice != null ? Number(row.totalPrice).toFixed(2) : '-' }}</template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 8. 长时间无订单枪站排名 -->
    <div class="ranking-section" v-if="idleStationData.length > 0">
      <div class="section-title">长时间无订单站点 Top20（近 30 日）</div>
      <el-table :data="idleStationData" stripe border size="small" style="width: 100%">
        <el-table-column label="#" width="50" align="center">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="站点" min-width="250" show-overflow-tooltip prop="stationName" />
        <el-table-column label="订单量" width="120" align="right">
          <template #default="{ row }">{{ formatNum(row.orderCnt) }}</template>
        </el-table-column>
        <el-table-column label="电量(kWh)" width="140" align="right">
          <template #default="{ row }">{{ formatNum(row.chargedPower) }}</template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { getOverview, getMonthlyTrend, getDaily, getScenario, getActiveUsers, getAppActive, getMauTrend, getYearlyComparison, getRevenueTrend, getUtilizationTrend, getRegionDistribution, getStationRanking, getHourlyDistribution, getHourlyOrderComparison, getHourlyChargingOrderComparison, getIdleStationRanking } from '@/api/biz-analysis.js'

const loading = ref(false)
const overviewLoading = ref(true)
const overviewFailed = ref(false)
const overview = ref({})
const idleStationData = ref([])
const monthlyData = ref([])
const dailyData = ref([])
const scenario = ref({})
const activeUsers = ref([])
const appActiveData = ref([])
const mauData = ref([])
const yearlyData = ref({})
const revenueData = ref([])
const utilizationData = ref([])
const regionData = ref([])
const stationData = ref([])
const hourlyData = ref([])
const hourlyCompData = ref({})
const hourlyChargingCompData = ref({})

const monthlyChartRef = ref(null)
const yearlyChartRef = ref(null)
const dailyChartRef = ref(null)
const appActiveChartRef = ref(null)
const mauChartRef = ref(null)
const revenueChartRef = ref(null)
const utilizationChartRef = ref(null)
const hourlyChartRef = ref(null)
const hourlyCompChartRef = ref(null)
const hourlyChargingCompChartRef = ref(null)

let monthlyChart = null
let yearlyChart = null
let dailyChart = null
let appActiveChart = null
let mauChart = null
let revenueChart = null
let utilizationChart = null
let hourlyChart = null
let hourlyCompChart = null
let hourlyChargingCompChart = null

const formatNum = (v) => {
  if (v == null) return '-'
  const n = Number(v)
  if (isNaN(n)) return '-'
  return Math.round(n).toLocaleString()
}

const tradeModeLabel = (mode) => {
  const map = { '1': '交易模式', '2': '佣金模式', '3': '不结算模式', 1: '交易模式', 2: '佣金模式', 3: '不结算模式' }
  return map[mode] || `模式${mode}`
}

const channelTableData = computed(() => {
  if (!scenario.value.byChannel || scenario.value.byChannel.length === 0) return []
  const row = scenario.value.byChannel[0]
  return [
    { channel: '零售', order: formatNum(row.retailOrder), power: formatNum(row.retailPower) },
    { channel: '非零售', order: formatNum(row.nonRetailOrder), power: formatNum(row.nonRetailPower) },
    { channel: '互联', order: formatNum(row.twjsOrder), power: formatNum(row.twjsPower) },
    { channel: '新电途', order: formatNum(row.xdtOrder), power: formatNum(row.xdtPower) }
  ]
})

const renderMonthlyChart = () => {
  if (!monthlyChartRef.value || monthlyData.value.length === 0) return
  if (monthlyChart) monthlyChart.dispose()
  monthlyChart = echarts.init(monthlyChartRef.value)
  const months = monthlyData.value.map(d => d.monthStr)
  const orders = monthlyData.value.map(d => Number(d.orderCnt || 0))
  const powers = monthlyData.value.map(d => Number(d.chargedPower || 0))
  monthlyChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['订单量', '电量(kWh)'] },
    xAxis: { type: 'category', data: months },
    yAxis: [
      { type: 'value', name: '订单量', position: 'left' },
      { type: 'value', name: '电量', position: 'right' }
    ],
    series: [
      { name: '订单量', type: 'bar', data: orders, itemStyle: { color: '#409EFF' } },
      { name: '电量(kWh)', type: 'line', yAxisIndex: 1, data: powers, itemStyle: { color: '#67C23A' } }
    ]
  })
}

const renderYearlyChart = () => {
  if (!yearlyChartRef.value || !yearlyData.value.comparison || yearlyData.value.comparison.length === 0) return
  if (yearlyChart) yearlyChart.dispose()
  yearlyChart = echarts.init(yearlyChartRef.value)
  const data = yearlyData.value.comparison
  const months = data.map(d => d.month)
  const thisYearOrders = data.map(d => Number(d.thisYearOrder || 0))
  const lastYearOrders = data.map(d => Number(d.lastYearOrder || 0))
  const thisYearPowers = data.map(d => Number(d.thisYearPower || 0))
  const lastYearPowers = data.map(d => Number(d.lastYearPower || 0))
  const cy = yearlyData.value.currentYear
  const ly = yearlyData.value.lastYear
  yearlyChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: [cy + ' 订单', ly + ' 订单', cy + ' 电量', ly + ' 电量'] },
    xAxis: { type: 'category', data: months },
    yAxis: [
      { type: 'value', name: '订单量', position: 'left' },
      { type: 'value', name: '电量', position: 'right' }
    ],
    series: [
      { name: cy + ' 订单', type: 'bar', data: thisYearOrders, itemStyle: { color: '#409EFF' } },
      { name: ly + ' 订单', type: 'bar', data: lastYearOrders, itemStyle: { color: '#A0CFFF' } },
      { name: cy + ' 电量', type: 'line', yAxisIndex: 1, data: thisYearPowers, itemStyle: { color: '#67C23A' } },
      { name: ly + ' 电量', type: 'line', yAxisIndex: 1, data: lastYearPowers, itemStyle: { color: '#B3E19D' }, lineStyle: { type: 'dashed' } }
    ]
  })
}

const renderDailyChart = () => {
  if (!dailyChartRef.value || dailyData.value.length === 0) return
  if (dailyChart) dailyChart.dispose()
  dailyChart = echarts.init(dailyChartRef.value)
  const dates = dailyData.value.map(d => d.statDate)
  const orders = dailyData.value.map(d => Number(d.orderCnt || 0))
  const powers = dailyData.value.map(d => Number(d.chargedPower || 0))
  dailyChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['订单量', '电量(kWh)'] },
    xAxis: { type: 'category', data: dates },
    yAxis: [
      { type: 'value', name: '订单量', position: 'left' },
      { type: 'value', name: '电量', position: 'right' }
    ],
    series: [
      { name: '订单量', type: 'line', data: orders, smooth: true, itemStyle: { color: '#409EFF' } },
      { name: '电量(kWh)', type: 'line', yAxisIndex: 1, data: powers, smooth: true, itemStyle: { color: '#E6A23C' } }
    ]
  })
}

const renderAppActiveChart = () => {
  if (!appActiveChartRef.value || appActiveData.value.length === 0) return
  if (appActiveChart) appActiveChart.dispose()
  appActiveChart = echarts.init(appActiveChartRef.value)
  const dates = appActiveData.value.map(d => d.statDate)
  const dau = appActiveData.value.map(d => Number(d.dau || 0))
  const clicks = appActiveData.value.map(d => Number(d.adClick || 0))
  appActiveChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['DAU', '广告点击'] },
    xAxis: { type: 'category', data: dates },
    yAxis: { type: 'value' },
    series: [
      { name: 'DAU', type: 'line', data: dau, smooth: true, itemStyle: { color: '#409EFF' } },
      { name: '广告点击', type: 'line', data: clicks, smooth: true, itemStyle: { color: '#F56C6C' } }
    ]
  })
}

const renderMauChart = () => {
  if (!mauChartRef.value || mauData.value.length === 0) return
  if (mauChart) mauChart.dispose()
  mauChart = echarts.init(mauChartRef.value)
  const months = mauData.value.map(d => d.month)
  const mau = mauData.value.map(d => Number(d.avgDau || 0))
  mauChart.setOption({
    tooltip: { trigger: 'axis', formatter: (params) => {
      const p = params[0]
      return `${p.name}<br/>月均 DAU: ${Number(p.value).toLocaleString()}`
    }},
    xAxis: { type: 'category', data: months },
    yAxis: { type: 'value', name: '月均日活跃用户', axisLabel: { formatter: (v) => (v / 10000).toFixed(0) + '万' } },
    series: [
      { name: '月均 DAU', type: 'bar', data: mau, itemStyle: { color: '#67C23A', borderRadius: [4, 4, 0, 0] }, barWidth: '40%' }
    ]
  })
}

const renderRevenueChart = () => {
  if (!revenueChartRef.value || revenueData.value.length === 0) return
  if (revenueChart) revenueChart.dispose()
  revenueChart = echarts.init(revenueChartRef.value)
  const dates = revenueData.value.map(d => d.statDate)
  const income = revenueData.value.map(d => Number(d.income || 0))
  const atv = revenueData.value.map(d => Number(d.avgOrderValue || 0))
  revenueChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['收入(元)', '客单价(元)'], top: 0 },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: 45 } },
    yAxis: [
      { type: 'value', name: '收入(元)' },
      { type: 'value', name: '客单价(元)' }
    ],
    series: [
      { name: '收入(元)', type: 'bar', data: income, itemStyle: { color: '#409EFF', borderRadius: [4, 4, 0, 0] } },
      { name: '客单价(元)', type: 'line', yAxisIndex: 1, data: atv, smooth: true, itemStyle: { color: '#E6A23C' } }
    ]
  })
}

const renderUtilizationChart = () => {
  if (!utilizationChartRef.value || utilizationData.value.length === 0) return
  if (utilizationChart) utilizationChart.dispose()
  utilizationChart = echarts.init(utilizationChartRef.value)
  const dates = utilizationData.value.map(d => d.statDate)
  const rates = utilizationData.value.map(d => Number(d.utilizationRate || 0))
  utilizationChart.setOption({
    tooltip: { trigger: 'axis', formatter: (params) => `${params[0].name}<br/>利用率: ${Number(params[0].value).toFixed(1)}%` },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: 45 } },
    yAxis: { type: 'value', name: '利用率(%)', max: 100 },
    series: [
      { name: '利用率', type: 'line', data: rates, smooth: true, areaStyle: { opacity: 0.3 }, itemStyle: { color: '#67C23A' } }
    ]
  })
}

const renderHourlyChart = () => {
  if (!hourlyChartRef.value || hourlyData.value.length === 0) return
  if (hourlyChart) hourlyChart.dispose()
  hourlyChart = echarts.init(hourlyChartRef.value)
  const hours = hourlyData.value.map(d => d.hour + ':00')
  const orders = hourlyData.value.map(d => Number(d.orderCnt || 0))
  const power = hourlyData.value.map(d => Number(d.chargedPower || 0))
  hourlyChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['订单量', '电量(kWh)'], top: 0 },
    xAxis: { type: 'category', data: hours },
    yAxis: [
      { type: 'value', name: '订单量' },
      { type: 'value', name: '电量(kWh)' }
    ],
    series: [
      { name: '订单量', type: 'bar', data: orders, itemStyle: { color: '#409EFF', borderRadius: [4, 4, 0, 0] } },
      { name: '电量(kWh)', type: 'line', yAxisIndex: 1, data: power, smooth: true, itemStyle: { color: '#F56C6C' } }
    ]
  })
}

const renderHourlyCompChart = () => {
  if (!hourlyCompChartRef.value || !hourlyCompData.value.hours || hourlyCompData.value.hours.length === 0) return
  if (hourlyCompChart) hourlyCompChart.dispose()
  hourlyCompChart = echarts.init(hourlyCompChartRef.value)
  const hours = hourlyCompData.value.hours
  const labels = hours.map(h => h.hour + ':00')
  const todayOrders = hours.map(h => Number(h.todayOrder || 0))
  const yesterdayOrders = hours.map(h => Number(h.yesterdayOrder || 0))
  const alertBgColors = hours.map(h => h.alert ? 'rgba(245,108,108,0.15)' : 'transparent')
  hourlyCompChart.setOption({
    tooltip: { trigger: 'axis', formatter: (params) => {
      const idx = params[0].dataIndex
      const h = hours[idx]
      let s = `${params[0].name}<br/>`
      params.forEach(p => { s += `${p.marker}${p.seriesName}: ${Number(p.value).toLocaleString()}<br/>` })
      if (h.alert) s += '<span style="color:#F56C6C;font-weight:bold">⚠ 落后 50%+</span>'
      return s
    }},
    legend: { data: ['今日订单', '昨日订单'], top: 0 },
    grid: { top: 40, bottom: 30, left: 50, right: 20 },
    xAxis: { type: 'category', data: labels },
    yAxis: { type: 'value', name: '订单量' },
    series: [
      {
        name: '今日订单', type: 'bar', data: todayOrders,
        itemStyle: {
          color: (params) => hours[params.dataIndex].alert ? '#F56C6C' : '#409EFF',
          borderRadius: [4, 4, 0, 0]
        }
      },
      { name: '昨日订单', type: 'bar', data: yesterdayOrders, itemStyle: { color: '#C0C4CC', borderRadius: [4, 4, 0, 0] } }
    ],
    markArea: { silent: true, data: hours.map((h, i) => h.alert ? [{ xAxis: i, itemStyle: { color: 'rgba(245,108,108,0.08)' } }, { xAxis: i }] : null).filter(Boolean) }
  })
}

const renderHourlyChargingCompChart = () => {
  if (!hourlyChargingCompChartRef.value || !hourlyChargingCompData.value.hours || hourlyChargingCompData.value.hours.length === 0) return
  if (hourlyChargingCompChart) hourlyChargingCompChart.dispose()
  hourlyChargingCompChart = echarts.init(hourlyChargingCompChartRef.value)
  const hours = hourlyChargingCompData.value.hours
  const labels = hours.map(h => h.hour + ':00')
  const todayCharging = hours.map(h => Number(h.todayCharging || 0))
  const yesterdayCharging = hours.map(h => Number(h.yesterdayCharging || 0))
  hourlyChargingCompChart.setOption({
    tooltip: { trigger: 'axis', formatter: (params) => {
      const idx = params[0].dataIndex
      const h = hours[idx]
      let s = `${params[0].name}<br/>`
      params.forEach(p => { s += `${p.marker}${p.seriesName}: ${Number(p.value).toLocaleString()}<br/>` })
      if (h.alert) s += '<span style="color:#F56C6C;font-weight:bold">⚠ 差异≥10000</span>'
      return s
    }},
    legend: {
      data: [
        { name: '今日充电中', itemStyle: { color: '#67C23A' } },
        { name: '昨日充电中', itemStyle: { color: '#C0C4CC' } }
      ],
      top: 0
    },
    grid: { top: 40, bottom: 30, left: 50, right: 20 },
    xAxis: { type: 'category', data: labels },
    yAxis: { type: 'value', name: '充电中订单数' },
    series: [
      {
        name: '今日充电中', type: 'bar', data: todayCharging,
        itemStyle: {
          color: (params) => hours[params.dataIndex].alert ? '#F56C6C' : '#67C23A',
          borderRadius: [4, 4, 0, 0]
        }
      },
      { name: '昨日充电中', type: 'bar', data: yesterdayCharging, itemStyle: { color: '#C0C4CC', borderRadius: [4, 4, 0, 0] } }
    ]
  })
}

const loadOverview = async () => {
  overviewLoading.value = true
  overviewFailed.value = false
  try {
    const res = await getOverview()
    overview.value = res.data || {}
  } catch (e) {
    console.error('业务监控概览加载失败:', e)
    overviewFailed.value = true
  } finally {
    overviewLoading.value = false
  }
}

const loadCharts = async () => {
  const results = await Promise.allSettled([
    getMonthlyTrend(), getDaily(30), getScenario(), getActiveUsers(20), getAppActive(30), getMauTrend(), getYearlyComparison(),
    getRevenueTrend(30), getUtilizationTrend(30), getRegionDistribution(30), getStationRanking(30, 20), getHourlyDistribution(7),
    getHourlyOrderComparison(), getHourlyChargingOrderComparison(), getIdleStationRanking(30, 20)
  ])
  const val = (r, fallback) => (r.status === 'fulfilled' ? (r.value.data ?? fallback) : fallback)
  const [mtRes, dRes, scRes, auRes, aaRes, mauRes, ycRes, revRes, utilRes, regRes, staRes, hrRes, hcRes, hccRes, idleRes] = results
  monthlyData.value = val(mtRes, [])
  dailyData.value = val(dRes, [])
  scenario.value = val(scRes, {})
  activeUsers.value = val(auRes, [])
  appActiveData.value = val(aaRes, [])
  mauData.value = val(mauRes, [])
  yearlyData.value = val(ycRes, {})
  revenueData.value = val(revRes, [])
  utilizationData.value = val(utilRes, [])
  regionData.value = val(regRes, [])
  stationData.value = val(staRes, [])
  hourlyData.value = val(hrRes, [])
  hourlyCompData.value = val(hcRes, {})
  hourlyChargingCompData.value = val(hccRes, {})
  idleStationData.value = val(idleRes, [])

  const failedCount = results.filter((r) => r.status === 'rejected').length
  if (failedCount > 0) {
    ElMessage.warning(`${failedCount} 个图表数据加载失败，可点击刷新重试`)
  }

  await nextTick()
  renderMonthlyChart()
  renderYearlyChart()
  renderDailyChart()
  renderAppActiveChart()
  renderMauChart()
  renderRevenueChart()
  renderUtilizationChart()
  renderHourlyChart()
  renderHourlyCompChart()
  renderHourlyChargingCompChart()
}

const fetchAll = async () => {
  loading.value = true
  try {
    await Promise.all([loadOverview(), loadCharts()])
  } finally {
    loading.value = false
  }
}

const handleResize = () => {
  monthlyChart?.resize()
  yearlyChart?.resize()
  dailyChart?.resize()
  appActiveChart?.resize()
  mauChart?.resize()
  revenueChart?.resize()
  utilizationChart?.resize()
  hourlyChart?.resize()
  hourlyCompChart?.resize()
}

onMounted(() => {
  fetchAll()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  monthlyChart?.dispose()
  yearlyChart?.dispose()
  dailyChart?.dispose()
  appActiveChart?.dispose()
  mauChart?.dispose()
  revenueChart?.dispose()
  utilizationChart?.dispose()
  hourlyChart?.dispose()
  hourlyCompChart?.dispose()
})
</script>

<style scoped>
.biz-analysis-page { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { margin: 0; font-size: 20px; }
.summary-cards { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; margin-bottom: 20px; }
.summary-cards.four-col { grid-template-columns: repeat(4, 1fr); }
.summary-card {
  background: #fff; border: 1px solid #ebeef5; border-radius: 8px;
  padding: 16px; text-align: center;
}
.card-label { font-size: 13px; color: #909399; margin-bottom: 8px; }
.card-value { font-size: 24px; font-weight: 700; color: #303133; }
.card-value.highlight { color: #409EFF; }
.card-date { font-size: 11px; color: #c0c4cc; margin-top: 4px; }
.chart-section { margin-bottom: 20px; }
.section-title { font-size: 15px; font-weight: 600; margin-bottom: 10px; color: #303133; }
.chart-container { height: 320px; background: #fff; border: 1px solid #ebeef5; border-radius: 8px; }
.chart-row { display: flex; gap: 16px; margin-bottom: 20px; }
.chart-row.two-col .chart-section { flex: 1; min-width: 0; }
.chart-row.three-col { align-items: flex-start; }
.chart-row.three-col .ranking-section { flex: 1; min-width: 0; }
.scenario-section { margin-bottom: 20px; }
.scenario-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.scenario-card { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; padding: 12px; }
.sub-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; color: #606266; }
.ranking-section { margin-bottom: 20px; }
.yoy-badge { display: inline-block; font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 4px; margin-left: 12px; vertical-align: middle; }
.yoy-badge.up { background: #f0f9eb; color: #67C23A; }
.yoy-badge.down { background: #fef0f0; color: #F56C6C; }
</style>
