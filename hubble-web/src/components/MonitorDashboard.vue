<template>
  <div class="monitor-dashboard">
    <!-- 顶部 -->
    <div class="dashboard-header">
      <div class="filter-area">
        <el-dropdown @command="handleCommand">
          <span class="time-filter">
            监控大盘 <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="/monitor">监控大盘</el-dropdown-item>
              <el-dropdown-item command="/abnormal">异常大盘</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-select v-model="timeRange" size="small" style="width: 100px" @change="loadTimeline">
          <el-option label="近15分钟" value="15m" />
          <el-option label="近1小时" value="1h" />
          <el-option label="近4小时" value="4h" />
        </el-select>
        <span class="refresh-tip">{{ countdown > 0 ? `${countdown}s 后刷新` : '刷新中…' }}</span>
      </div>
    </div>

    <!-- 分钟级健康时间线 -->
    <div class="timeline-section">
      <div class="section-title">
        健康时间线
        <span class="hint">每分钟取所有服务中最差状态，有红盘服务则该分钟为红</span>
      </div>

      <!-- 统计概览 -->
      <div class="summary-cards">
        <div class="summary-card summary-red">
          <div class="summary-label">红盘分钟</div>
          <div class="summary-value">{{ redMinutes }}</div>
          <div class="summary-desc">存在服务错误数 ≥ {{ minuteRedThreshold }}</div>
        </div>
        <div class="summary-card summary-yellow">
          <div class="summary-label">粉盘分钟</div>
          <div class="summary-value">{{ yellowMinutes }}</div>
          <div class="summary-desc">存在服务错误数 ≥ {{ minuteYellowThreshold }}</div>
        </div>
        <div class="summary-card summary-green">
          <div class="summary-label">正常分钟</div>
          <div class="summary-value">{{ greenMinutes }}</div>
        </div>
      </div>

      <!-- 时间线网格 -->
      <div v-loading="timelineLoading" class="timeline-grid">
        <div
          v-for="point in timeline"
          :key="point.minute"
          class="timeline-cell"
          :class="'cell-' + point.status.toLowerCase()"
          @click="handleMinuteClick(point)"
        >
          <div class="cell-time">{{ formatMinute(point.minute) }}</div>
          <div class="cell-errors">{{ point.totalErrors }} 错误</div>
          <div class="cell-services">
            <el-tag
              v-for="svc in point.redServices"
              :key="'r-' + svc"
              type="danger"
              size="small"
              effect="dark"
              class="svc-tag"
            >{{ svc }}</el-tag>
            <el-tag
              v-for="svc in point.yellowServices"
              :key="'y-' + svc"
              type="warning"
              size="small"
              effect="dark"
              class="svc-tag"
            >{{ svc }}</el-tag>
          </div>
          <div class="cell-status-label">{{ statusLabel(point.status) }}</div>
        </div>

        <div v-if="!timelineLoading && timeline.length === 0" class="empty-tip">
          暂无数据
        </div>
      </div>
    </div>

    <!-- 监控项列表 -->
    <div class="hot-services">
      <div class="section-title">监控项配置 <span class="hint">点击行下钻服务日志</span></div>
      <el-table
        v-loading="loading"
        :data="rows"
        style="width: 100%"
        highlight-current-row
        :row-class-name="rowClass"
        @row-click="openServiceLogs"
      >
        <el-table-column prop="title" label="监控项" min-width="200" show-overflow-tooltip />
        <el-table-column label="当前日志数" width="140">
          <template #default="{ row }">
            <span :class="row.valueClass">{{ row.currentLogCount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="alertThreshold" label="阈值" width="100" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.tagType" size="small">{{ row.statusLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="todayMax" label="今日峰值" width="110" />
        <el-table-column label="趋势" width="220">
          <template #default="{ row }">
            <div :ref="el => setChartRef(el, row.id)" class="trend-chart"></div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 下钻抽屉 -->
    <el-drawer v-model="drill.visible" :title="drill.title" size="58%" direction="rtl" destroy-on-close>
      <div class="drill-hint">
        <template v-if="drill.mode === 'service'">
          共 {{ drillLogs.length }} 条；点击
          <el-tag size="small" type="info">traceId</el-tag> 可下钻跨服务链路
        </template>
        <template v-else>
          <el-button text size="small" @click="backToServiceLogs">← 返回</el-button>
          <span class="trace-code">trace: {{ drill.traceId }}</span>
        </template>
      </div>
      <el-table v-loading="drillLoading" :data="drillLogs" size="small" max-height="78vh" border>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ formatTime(row.time) }}</template>
        </el-table-column>
        <el-table-column label="级别" width="80">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)" size="small">{{ row.level || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="服务" width="180" prop="service" show-overflow-tooltip />
        <el-table-column label="traceId" width="150">
          <template #default="{ row }">
            <span v-if="row.trace" class="trace-link" @click.stop="drillToTrace(row.trace)">{{ shortTrace(row.trace) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="日志" prop="message" show-overflow-tooltip />
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElNotification } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { getAlertConfigList, getAlertStatistics, getAlertDataList, getMinuteTimeline } from '@/api/alert.js'
import { getServiceLogs, getTraceLogs } from '@/api/drill.js'
import { subscribeAlerts } from '@/api/monitor-sse.js'
import { getHealthStatus, STATUS_TAG_TYPE, STATUS_LABEL, STATUS_VALUE_CLASS } from '@/utils/health.js'

const router = useRouter()
const timeRange = ref('15m')
const loading = ref(false)
const timelineLoading = ref(false)
const configs = ref([])
const rows = ref([])
const countdown = ref(30)
const flashMap = reactive({})

const timeline = ref([])
const minuteRedThreshold = ref(0)
const minuteYellowThreshold = ref(0)

const redMinutes = computed(() => timeline.value.filter(p => p.status === 'RED').length)
const yellowMinutes = computed(() => timeline.value.filter(p => p.status === 'YELLOW').length)
const greenMinutes = computed(() => timeline.value.filter(p => p.status === 'GREEN').length)

const chartRefs = new Map()
const chartInstances = new Map()
const REFRESH_INTERVAL = 30

const handleCommand = (command) => router.push(command)
const setChartRef = (el, configId) => { if (el) chartRefs.set(configId, el) }

const statusLabel = (status) => {
  if (status === 'RED') return '红盘'
  if (status === 'YELLOW') return '粉盘'
  return '正常'
}

const formatMinute = (minute) => {
  if (!minute) return ''
  return minute.substring(11, 16)
}

const loadTimeline = async () => {
  timelineLoading.value = true
  try {
    const res = await getMinuteTimeline(timeRange.value)
    if (res.code === 200) {
      const data = res.data
      timeline.value = data.timeline || []
      minuteRedThreshold.value = data.minuteRedThreshold || 0
      minuteYellowThreshold.value = data.minuteYellowThreshold || 0
    }
  } catch (e) {
    console.error('加载时间线失败:', e)
  } finally {
    timelineLoading.value = false
  }
}

const handleMinuteClick = (point) => {
  const allProblemServices = [...(point.redServices || []), ...(point.yellowServices || [])]
  if (allProblemServices.length > 0) {
    const mainService = point.redServices?.[0] || point.yellowServices?.[0]
    router.push({
      path: '/gateway/logs',
      query: {
        appName: mainService,
        keyword: 'level: ERROR'
      }
    })
  }
}

// ===== 监控项表格 =====
const loadConfigs = async () => {
  try {
    const res = await getAlertConfigList({ enabled: true, current: 1, size: 50 })
    if (res?.code === 200) configs.value = res.data?.records || res.data?.list || []
  } catch (e) {
    console.error('加载监控配置失败:', e)
    configs.value = []
  }
}

const loadConfigRows = async () => {
  await loadConfigs()
  const results = await Promise.all(
    configs.value.map(async (cfg) => {
      const [statRes, dataRes] = await Promise.allSettled([
        getAlertStatistics(cfg.id, '15m'),
        getAlertDataList({ alertConfigId: cfg.id, timeRange: '15m', current: 1, size: 50 })
      ])
      const stat = statRes.status === 'fulfilled' && statRes.value.code === 200 ? statRes.value.data : {}
      const series = dataRes.status === 'fulfilled' && dataRes.value.code === 200 ? dataRes.value.data.records || [] : []
      return buildRow(cfg, stat, series)
    })
  )
  rows.value = results
  await nextTick()
  renderCharts()
}

const buildRow = (cfg, stat, series) => {
  const status = getHealthStatus(stat.currentLogCount, stat.alertThreshold)
  return {
    id: cfg.id, title: cfg.title,
    currentLogCount: stat.currentLogCount ?? 0,
    alertThreshold: stat.alertThreshold ?? cfg.alertThreshold,
    todayMax: stat.todayMax ?? 0,
    todayAlertCount: stat.todayAlertCount ?? 0,
    status, statusLabel: STATUS_LABEL[status],
    tagType: STATUS_TAG_TYPE[status],
    valueClass: STATUS_VALUE_CLASS[status],
    series
  }
}

const renderCharts = () => {
  for (const row of rows.value) {
    const dom = chartRefs.get(row.id)
    if (!dom) continue
    let chart = chartInstances.get(row.id)
    if (!chart || chart.isDisposed()) { chart = echarts.init(dom); chartInstances.set(row.id, chart) }
    chart.setOption({
      grid: { left: 4, right: 4, top: 4, bottom: 4 },
      xAxis: { type: 'category', show: false, data: row.series.map(p => p.collectedAt) },
      yAxis: { type: 'value', show: false },
      tooltip: { trigger: 'axis', formatter: p => p[0] ? `${p[0].data}` : '' },
      series: [{ type: 'line', data: row.series.map(p => p.logCount), smooth: true, showSymbol: false, lineStyle: { width: 2 }, areaStyle: { opacity: 0.15 } }]
    }, true)
  }
}

const rowClass = ({ row }) => (flashMap[row.id] ? 'row-flash' : '')

// ===== 下钻 =====
const drill = reactive({ visible: false, mode: 'service', title: '', configId: null, configTitle: '', traceId: '' })
const drillLogs = ref([])
const drillLoading = ref(false)

const openServiceLogs = async (row) => {
  if (!row?.id) return
  drill.mode = 'service'; drill.configId = row.id; drill.configTitle = row.title
  drill.traceId = ''; drill.title = `服务日志：${row.title}`; drill.visible = true; drillLoading.value = true
  try {
    const res = await getServiceLogs({ configId: row.id, timeRange: '15m', limit: 100 })
    if (res.code === 200) drillLogs.value = res.data || []
  } finally { drillLoading.value = false }
}

const drillToTrace = async (traceId) => {
  if (!traceId) return
  drill.mode = 'trace'; drill.traceId = traceId; drill.title = `链路：${shortTrace(traceId)}`; drillLoading.value = true
  try {
    const res = await getTraceLogs({ traceId, timeRange: '1h', limit: 200 })
    if (res.code === 200) drillLogs.value = res.data || []
  } finally { drillLoading.value = false }
}

const backToServiceLogs = () => { if (drill.configId) openServiceLogs({ id: drill.configId, title: drill.configTitle }) }
const levelType = (level) => { const l = (level || '').toUpperCase(); if (l.includes('ERROR')) return 'danger'; if (l.includes('WARN')) return 'warning'; return 'info' }
const shortTrace = (t) => (t && t.length > 12 ? t.slice(0, 8) + '…' + t.slice(-4) : t)
const formatTime = (ms) => { if (!ms) return '-'; const d = new Date(ms); const p = n => String(n).padStart(2, '0'); return `${p(d.getMonth()+1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}` }

// ===== SSE =====
const handleAlert = (payload) => {
  ElNotification({ type: 'error', title: `告警：${payload.title || '监控项'}`, message: `日志数 ${payload.logCount} ≥ 阈值 ${payload.threshold}`, duration: 5000 })
  if (payload.configId) { flashMap[payload.configId] = true; setTimeout(() => delete flashMap[payload.configId], 5000); loadAll() }
}

// ===== 刷新 =====
const loadAll = async () => { loading.value = true; try { await Promise.all([loadTimeline(), loadConfigRows()]) } finally { loading.value = false } }

let timer = null, closeSse = null
const startPolling = () => { stopPolling(); countdown.value = REFRESH_INTERVAL; timer = setInterval(() => { countdown.value--; if (countdown.value <= 0) loadAll().then(() => countdown.value = REFRESH_INTERVAL) }, 1000) }
const stopPolling = () => { if (timer) { clearInterval(timer); timer = null } }
const handleVisibility = () => { if (document.visibilityState === 'visible') { loadAll(); startPolling() } else stopPolling() }
const handleResize = () => chartInstances.forEach(c => { if (!c.isDisposed()) c.resize() })

onMounted(() => { loadAll().then(startPolling); closeSse = subscribeAlerts(handleAlert); window.addEventListener('resize', handleResize); document.addEventListener('visibilitychange', handleVisibility) })
onBeforeUnmount(() => { stopPolling(); if (closeSse) closeSse(); window.removeEventListener('resize', handleResize); document.removeEventListener('visibilitychange', handleVisibility); chartInstances.forEach(c => { if (!c.isDisposed()) c.dispose() }); chartInstances.clear() })
</script>

<style scoped>
.monitor-dashboard {
  background: white; padding: 16px; border-radius: 4px;
  height: 100%; display: flex; flex-direction: column; gap: 16px; overflow: auto;
}

.dashboard-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.filter-area { display: flex; align-items: center; gap: 16px; }
.time-filter { display: flex; align-items: center; gap: 5px; cursor: pointer; font-size: 13px; color: #606266; }
.refresh-tip { font-size: 12px; color: #999; }

.section-title { font-size: 16px; font-weight: 600; color: #333; margin-bottom: 12px; }
.section-title .hint { font-size: 12px; font-weight: normal; color: #999; margin-left: 8px; }

/* ===== 概览 ===== */
.summary-cards { display: flex; gap: 16px; margin-bottom: 16px; }
.summary-card { flex: 1; padding: 14px 18px; border-radius: 8px; border-left: 4px solid #d9d9d9; background: #fafafa; }
.summary-card.summary-red { border-left-color: #f56c6c; background: #fef0f0; }
.summary-card.summary-yellow { border-left-color: #e6a23c; background: #fdf6ec; }
.summary-card.summary-green { border-left-color: #67c23a; background: #f0f9eb; }
.summary-label { font-size: 13px; color: #666; margin-bottom: 4px; }
.summary-value { font-size: 28px; font-weight: bold; }
.summary-red .summary-value { color: #f56c6c; }
.summary-yellow .summary-value { color: #e6a23c; }
.summary-green .summary-value { color: #67c23a; }
.summary-desc { font-size: 11px; color: #999; margin-top: 2px; }

/* ===== 时间线网格 ===== */
.timeline-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
  margin-bottom: 8px;
}

.timeline-cell {
  border-radius: 6px;
  border: 2px solid #e4e7ed;
  padding: 10px;
  cursor: pointer;
  transition: all 0.2s ease;
  position: relative;
  min-height: 90px;
}
.timeline-cell:hover { transform: translateY(-1px); box-shadow: 0 2px 8px rgba(0,0,0,0.1); }

.cell-red { border-color: #f56c6c; background: #fef0f0; }
.cell-yellow { border-color: #e6a23c; background: #fdf6ec; }
.cell-green { border-color: #c8e6c9; background: #f0f9eb; }

.cell-time { font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 4px; }
.cell-errors { font-size: 12px; color: #666; margin-bottom: 6px; }
.cell-services { display: flex; flex-wrap: wrap; gap: 2px; margin-bottom: 4px; }
.svc-tag { font-size: 10px; padding: 0 4px; height: 18px; line-height: 18px; }
.cell-status-label {
  position: absolute; top: 6px; right: 8px;
  font-size: 10px; font-weight: 600;
}
.cell-red .cell-status-label { color: #f56c6c; }
.cell-yellow .cell-status-label { color: #e6a23c; }
.cell-green .cell-status-label { color: #67c23a; }

.empty-tip { text-align: center; color: #999; font-size: 13px; padding: 24px 0; grid-column: 1 / -1; }

/* ===== 监控项表格 ===== */
.hot-services { margin-top: 8px; }
.trend-chart { width: 100%; height: 32px; }
.stat-value-danger { color: #f56c6c; }
.stat-value-warning { color: #e6a23c; }

:deep(.el-table__row) { cursor: pointer; }
:deep(.el-table__row.row-flash) { animation: flash-red 0.8s ease-in-out 3; }
@keyframes flash-red { 0%, 100% { background-color: transparent; } 50% { background-color: #fef0f0; } }

.drill-hint { font-size: 12px; color: #909399; margin-bottom: 12px; display: flex; align-items: center; gap: 8px; }
.trace-link { color: #1890ff; cursor: pointer; font-family: monospace; font-size: 12px; }
.trace-link:hover { text-decoration: underline; }
.trace-code { font-family: monospace; font-size: 12px; color: #606266; }
</style>
