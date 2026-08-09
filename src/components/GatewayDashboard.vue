<template>
  <div class="gateway-dashboard">
    <div class="dashboard-header">
      <div class="dashboard-title">
        <h2>监控大盘</h2>
      </div>
      <div class="header-controls">
        <span class="refresh-tip">{{ countdown > 0 ? `${countdown}s 后刷新` : '刷新中…' }}</span>
      </div>
    </div>

    <!-- ===== 网关概览 ===== -->
    <div class="gateway-section">
      <div class="section-title">网关概览</div>

      <div class="overview-cards">
        <div class="overview-card">
          <div class="card-title">总请求量</div>
          <div class="card-value">{{ formatNumber(overview.totalRequests) }}</div>
          <div class="card-trend" :class="trendClass(overview.totalTrend)">
            <span>{{ trendArrow(overview.totalTrend) }} {{ formatPercent(Math.abs(overview.totalTrend)) }}</span>
            <span class="trend-label">较上周期</span>
          </div>
        </div>
        <div class="overview-card">
          <div class="card-title">平均响应时间</div>
          <div class="card-value">{{ overview.avgResponseTime }}ms</div>
          <div class="card-trend" :class="trendClass(overview.avgTrend)">
            <span>{{ trendArrow(overview.avgTrend) }} {{ formatPercent(Math.abs(overview.avgTrend)) }}</span>
            <span class="trend-label">较上周期</span>
          </div>
        </div>
        <div class="overview-card">
          <div class="card-title">错误率</div>
          <div class="card-value">{{ formatPercent(overview.errorRate) }}</div>
          <div class="card-trend" :class="trendClass(overview.errorTrend)">
            <span>{{ trendArrow(overview.errorTrend) }} {{ formatPercent(Math.abs(overview.errorTrend)) }}</span>
            <span class="trend-label">较上周期</span>
          </div>
        </div>
        <div class="overview-card">
          <div class="card-title">QPS</div>
          <div class="card-value">{{ formatNumber(overview.qps) }}</div>
          <div class="card-trend" :class="trendClass(overview.qpsTrend)">
            <span>{{ trendArrow(overview.qpsTrend) }} {{ formatPercent(Math.abs(overview.qpsTrend)) }}</span>
            <span class="trend-label">较上周期</span>
          </div>
        </div>
      </div>

      <!-- 请求趋势图 -->
      <div class="trend-chart">
        <div class="chart-header">
          <h3>请求趋势</h3>
          <div class="chart-legend">
            <span class="legend-item"><span class="dot info"></span>INFO</span>
            <span class="legend-item"><span class="dot warn"></span>WARN</span>
            <span class="legend-item"><span class="dot error"></span>ERROR</span>
          </div>
        </div>
        <div ref="chartRef" class="chart-container"></div>
      </div>

      <!-- 热门接口 -->
      <div class="hot-apis">
        <div class="sub-title">热门接口</div>
        <el-table :data="hotApis" style="width: 100%" size="small">
          <el-table-column prop="path" label="接口路径" show-overflow-tooltip />
          <el-table-column prop="method" label="请求方法" width="100" />
          <el-table-column label="QPS" width="100">
            <template #default="{ row }">{{ Number(row.qps).toFixed(4) }}</template>
          </el-table-column>
          <el-table-column prop="avgTime" label="平均响应时间" width="120" />
          <el-table-column prop="errorRate" label="错误率" width="100" />
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import { getGatewayOverview, getGatewayTrend, getGatewayHotApis } from '@/api/gateway.js'

// ===== 网关数据 =====
const overview = ref({ totalRequests: 0, avgResponseTime: 0, errorRate: 0, qps: 0, totalTrend: 0, avgTrend: 0, errorTrend: 0, qpsTrend: 0 })
const hotApis = ref([])
const chartRef = ref(null)
const timeRange = ref('24h')
let chart = null

const formatNumber = (num) => num == null ? '0' : Number(num).toLocaleString()
const formatPercent = (num) => num == null ? '0%' : Number(num).toFixed(2) + '%'
const trendClass = (val) => val >= 0 ? 'up' : 'down'
const trendArrow = (val) => val >= 0 ? '↑' : '↓'

const initChart = (trendData) => {
  if (!chartRef.value) return
  const option = {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: '5%', right: '5%', bottom: '5%', top: '15%', containLabel: true },
    xAxis: { type: 'category', data: trendData?.timestamps || [], axisLabel: { interval: 2 } },
    yAxis: [
      { type: 'value', name: 'INFO', position: 'left', axisLabel: { formatter: (v) => v >= 10000 ? (v / 10000).toFixed(0) + 'w' : v } },
      { type: 'value', name: 'WARN/ERROR', position: 'right', axisLabel: { formatter: (v) => v >= 10000 ? (v / 10000).toFixed(0) + 'w' : v } }
    ],
    series: [
      { name: 'INFO', type: 'line', smooth: true, yAxisIndex: 0, data: trendData?.infoCounts || [], itemStyle: { color: '#409EFF' }, areaStyle: { opacity: 0.1 } },
      { name: 'WARN', type: 'line', smooth: true, yAxisIndex: 1, data: trendData?.warnCounts || [], itemStyle: { color: '#E6A23C' }, areaStyle: { opacity: 0.1 } },
      { name: 'ERROR', type: 'line', smooth: true, yAxisIndex: 1, data: trendData?.errorCounts || [], itemStyle: { color: '#F56C6C' }, areaStyle: { opacity: 0.1 } }
    ]
  }
  if (chart) chart.dispose()
  chart = echarts.init(chartRef.value)
  chart.setOption(option)
}

const fetchOverview = async () => {
  try {
    const res = await getGatewayOverview({ timeRange: timeRange.value }).catch(() => ({ data: null }))
    if (res?.data) overview.value = res.data
  } catch (e) { console.error('获取概览数据失败:', e) }
}

const fetchTrendAndHotApis = async () => {
  try {
    const [trendRes, hotApisRes] = await Promise.all([
      getGatewayTrend({ timeRange: timeRange.value }).catch(() => ({ data: null })),
      getGatewayHotApis({ timeRange: timeRange.value }).catch(() => ({ data: null }))
    ])
    initChart(trendRes?.data || {})
    hotApis.value = hotApisRes?.data || []
  } catch (e) { console.error('获取趋势/热门数据失败:', e) }
}

const fetchGatewayData = async () => {
  await fetchOverview()
  fetchTrendAndHotApis()
}

// ===== 刷新 =====
const countdown = ref(30)
const REFRESH_INTERVAL = 30
let timer = null

const loadAll = () => { fetchGatewayData() }

const startPolling = () => {
  stopPolling()
  countdown.value = REFRESH_INTERVAL
  timer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) { loadAll(); countdown.value = REFRESH_INTERVAL }
  }, 1000)
}
const stopPolling = () => { if (timer) { clearInterval(timer); timer = null } }

const handleVisibility = () => {
  if (document.visibilityState === 'visible') { loadAll(); startPolling() } else stopPolling()
}

onMounted(() => { loadAll(); startPolling(); window.addEventListener('resize', () => chart?.resize()); document.addEventListener('visibilitychange', handleVisibility) })
onBeforeUnmount(() => { stopPolling(); window.removeEventListener('resize', () => chart?.resize()); document.removeEventListener('visibilitychange', handleVisibility); chart?.dispose() })
</script>

<style scoped>
.gateway-dashboard {
  background: white; padding: 16px; border-radius: 4px;
  height: 100%; display: flex; flex-direction: column; gap: 16px; overflow: auto;
}

.dashboard-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.dashboard-title h2 { font-size: 16px; font-weight: 600; color: #333; margin: 0; }
.header-controls { display: flex; align-items: center; gap: 12px; }
.refresh-tip { font-size: 12px; color: #999; }

.section-title { font-size: 15px; font-weight: 600; color: #333; margin-bottom: 12px; }
.sub-title { font-size: 14px; font-weight: 600; color: #333; margin-bottom: 12px; }

/* ===== 网关概览 ===== */
.overview-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 16px; }
.overview-card { background: #fafafa; padding: 16px; border-radius: 4px; border: 1px solid #f0f0f0; }
.card-title { font-size: 14px; color: #666; margin-bottom: 8px; }
.card-value { font-size: 24px; font-weight: 600; color: #333; margin-bottom: 8px; }
.card-trend { font-size: 12px; display: flex; align-items: center; gap: 4px; }
.card-trend.up { color: #52c41a; }
.card-trend.down { color: #ff4d4f; }
.trend-label { color: #999; }

.trend-chart { background: white; border-radius: 4px; padding: 16px; margin-bottom: 16px; box-shadow: 0 2px 12px 0 rgba(0,0,0,0.1); }
.chart-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.chart-header h3 { font-size: 16px; margin: 0; font-weight: 500; }
.chart-legend { display: flex; gap: 16px; }
.legend-item { display: flex; align-items: center; gap: 4px; font-size: 12px; }
.dot { width: 8px; height: 8px; border-radius: 50%; }
.dot.info { background-color: #409EFF; }
.dot.warn { background-color: #E6A23C; }
.dot.error { background-color: #F56C6C; }
.chart-container { height: 300px; width: 100%; }

.hot-apis { margin-top: 8px; }
</style>
