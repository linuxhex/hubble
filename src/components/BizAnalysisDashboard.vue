<template>
  <div class="biz-analysis-page">
    <div class="page-header">
      <h2>经营分析</h2>
      <el-button size="small" @click="fetchAll" :loading="loading">刷新</el-button>
    </div>

    <!-- 1. 汇总卡片 -->
    <div class="summary-cards">
      <div class="summary-card">
        <div class="card-label">今日订单量</div>
        <div class="card-value">{{ formatNum(overview.orderCnt) }}</div>
        <div class="card-date">{{ overview.date || '-' }}</div>
      </div>
      <div class="summary-card">
        <div class="card-label">今日电量(kWh)</div>
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

    <!-- 2. 月度趋势 + 环比 -->
    <div class="chart-section">
      <div class="section-title">月度趋势（近 12 月）</div>
      <div ref="monthlyChartRef" class="chart-container"></div>
    </div>

    <!-- 2.5 年度同比对比 -->
    <div class="chart-section">
      <div class="section-title">
        年度同比（{{ yearlyData.currentYear || '-' }} vs {{ yearlyData.lastYear || '-' }}）
        <span v-if="yearlyData.orderYoy != null" class="yoy-badge" :class="yearlyData.orderYoy >= 0 ? 'up' : 'down'">
          订单同比 {{ yearlyData.orderYoy >= 0 ? '+' : '' }}{{ yearlyData.orderYoy }}%
        </span>
        <span v-if="yearlyData.powerYoy != null" class="yoy-badge" :class="yearlyData.powerYoy >= 0 ? 'up' : 'down'" style="margin-left:8px">
          电量同比 {{ yearlyData.powerYoy >= 0 ? '+' : '' }}{{ yearlyData.powerYoy }}%
        </span>
      </div>
      <div ref="yearlyChartRef" class="chart-container"></div>
    </div>

    <!-- 3. 每日订单电量 -->
    <div class="chart-section">
      <div class="section-title">每日订单 & 电量（近 30 日）</div>
      <div ref="dailyChartRef" class="chart-container"></div>
    </div>

    <!-- 4. 业务场景拆分 -->
    <div class="scenario-section">
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

    <!-- 5. 小程序活跃趋势 -->
    <div class="chart-section">
      <div class="section-title">DAU 日活趋势（近 30 日）</div>
      <div ref="appActiveChartRef" class="chart-container"></div>
    </div>

    <!-- 5.5 MAU 月活趋势 -->
    <div class="chart-section">
      <div class="section-title">MAU 月活趋势（近 6 月）</div>
      <div ref="mauChartRef" class="chart-container"></div>
    </div>

    <!-- 6. 充电最活跃用户排名 -->
    <div class="ranking-section">
      <div class="section-title">充电最活跃用户 Top20</div>
      <el-table :data="activeUsers" stripe border size="small" style="width: 100%">
        <el-table-column label="排名" width="70" align="center">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="用户ID" min-width="200" show-overflow-tooltip prop="userId" />
        <el-table-column label="订单数" width="120" align="right">
          <template #default="{ row }">{{ formatNum(row.orderCnt) }}</template>
        </el-table-column>
        <el-table-column label="总金额" width="140" align="right">
          <template #default="{ row }">{{ row.totalPrice != null ? Number(row.totalPrice).toFixed(2) : '-' }}</template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getOverview, getMonthlyTrend, getDaily, getScenario, getActiveUsers, getAppActive, getMauTrend, getYearlyComparison } from '@/api/biz-analysis.js'

const loading = ref(false)
const overview = ref({})
const monthlyData = ref([])
const dailyData = ref([])
const scenario = ref({})
const activeUsers = ref([])
const appActiveData = ref([])
const mauData = ref([])
const yearlyData = ref({})

const monthlyChartRef = ref(null)
const yearlyChartRef = ref(null)
const dailyChartRef = ref(null)
const appActiveChartRef = ref(null)
const mauChartRef = ref(null)

let monthlyChart = null
let yearlyChart = null
let dailyChart = null
let appActiveChart = null
let mauChart = null

const formatNum = (v) => {
  if (v == null) return '-'
  const n = Number(v)
  if (isNaN(n)) return '-'
  return Math.round(n).toLocaleString()
}

const tradeModeLabel = (mode) => {
  const map = { '1': '直连', '2': '互联A', '3': '互联B', 1: '直连', 2: '互联A', 3: '互联B' }
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
  const months = monthlyData.value.map(d => d.month)
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
  const dates = dailyData.value.map(d => d.date)
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
  const dates = appActiveData.value.map(d => d.date)
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
  const mau = mauData.value.map(d => Number(d.mau || 0))
  mauChart.setOption({
    tooltip: { trigger: 'axis', formatter: (params) => {
      const p = params[0]
      return `${p.name}<br/>MAU: ${Number(p.value).toLocaleString()}`
    }},
    xAxis: { type: 'category', data: months },
    yAxis: { type: 'value', name: '月活用户数', axisLabel: { formatter: (v) => (v / 10000).toFixed(0) + '万' } },
    series: [
      { name: 'MAU', type: 'bar', data: mau, itemStyle: { color: '#67C23A', borderRadius: [4, 4, 0, 0] }, barWidth: '40%' }
    ]
  })
}

const fetchAll = async () => {
  loading.value = true
  try {
    const [ovRes, mtRes, dRes, scRes, auRes, aaRes, mauRes, ycRes] = await Promise.all([
      getOverview(), getMonthlyTrend(), getDaily(30), getScenario(), getActiveUsers(20), getAppActive(30), getMauTrend(), getYearlyComparison()
    ])
    overview.value = ovRes.data || {}
    monthlyData.value = mtRes.data || []
    dailyData.value = dRes.data || []
    scenario.value = scRes.data || {}
    activeUsers.value = auRes.data || []
    appActiveData.value = aaRes.data || []
    mauData.value = mauRes.data || []
    yearlyData.value = ycRes.data || {}

    await nextTick()
    renderMonthlyChart()
    renderYearlyChart()
    renderDailyChart()
    renderAppActiveChart()
    renderMauChart()
  } catch (e) {
    console.error('经营分析加载失败:', e)
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
})
</script>

<style scoped>
.biz-analysis-page { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { margin: 0; font-size: 20px; }
.summary-cards { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; margin-bottom: 20px; }
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
.scenario-section { margin-bottom: 20px; }
.scenario-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.scenario-card { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; padding: 12px; }
.sub-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; color: #606266; }
.ranking-section { margin-bottom: 20px; }
.yoy-badge { display: inline-block; font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 4px; margin-left: 12px; vertical-align: middle; }
.yoy-badge.up { background: #f0f9eb; color: #67C23A; }
.yoy-badge.down { background: #fef0f0; color: #F56C6C; }
</style>
