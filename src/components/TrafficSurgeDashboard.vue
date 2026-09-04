<template>
  <div class="traffic-surge-page">
    <div class="page-header">
      <h2>流量暴涨监控</h2>
      <div class="header-actions">
        <el-radio-group v-model="compareMode" size="small" @change="handleModeChange">
          <el-radio-button value="day">今天 vs 昨天</el-radio-button>
          <el-radio-button value="week">本周 vs 上周</el-radio-button>
        </el-radio-group>
        <span class="refresh-info">更新于 {{ lastUpdated }} | {{ countdownText }}</span>
      </div>
    </div>

    <!-- 概览卡片 -->
    <div class="surge-summary">
      <div class="summary-card critical" :class="{ active: filterLevel === 'critical' }" @click="toggleFilter('critical')">
        <div class="summary-value">{{ criticalCount }}</div>
        <div class="summary-label">暴涨接口（>200%）</div>
      </div>
      <div class="summary-card warning" :class="{ active: filterLevel === 'warning' }" @click="toggleFilter('warning')">
        <div class="summary-value">{{ warningCount }}</div>
        <div class="summary-label">涨幅显著（>50%）</div>
      </div>
      <div class="summary-card normal" :class="{ active: filterLevel === null }" @click="toggleFilter(null)">
        <div class="summary-value">{{ tableData.length }}</div>
        <div class="summary-label">涨幅接口总数</div>
      </div>
    </div>

    <!-- 涨幅排名表 -->
    <div class="surge-table-section">
      <div class="section-header">
        <span class="section-title">流量涨幅排名</span>
        <span class="table-hint">点击行可下钻查看链路详情</span>
      </div>
      <el-table
        v-loading="loading"
        :data="filteredData"
        stripe
        border
        size="small"
        style="width: 100%"
        highlight-current-row
        @row-click="openDrillDown"
        row-class-name="clickable-row"
      >
        <el-table-column label="排名" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="getRankType(row.rank)" size="small" effect="dark">
              {{ row.rank }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="apiPath" label="接口" min-width="250" show-overflow-tooltip />
        <el-table-column label="流量涨幅" width="150" align="center" prop="degradationRate">
          <template #default="{ row }">
            <span :class="surgeClass(row.degradationRate)">
              ↑ {{ row.degradationRate.toFixed(1) }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column label="当前请求数" width="130" align="right" prop="currentCount">
          <template #default="{ row }">
            <span class="count-current">{{ row.currentCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="上期请求数" width="130" align="right" prop="previousCount">
          <template #default="{ row }">
            <span class="count-previous">{{ row.previousCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="当前P60" width="120" align="right">
          <template #default="{ row }">
            <span class="time-value">{{ row.currentAvgTime.toFixed(1) }} ms</span>
          </template>
        </el-table-column>
        <el-table-column label="上期P60" width="120" align="right">
          <template #default="{ row }">
            <span class="time-value muted">{{ row.previousAvgTime.toFixed(1) }} ms</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click.stop="openDrillDown(row)">下钻</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && filteredData.length === 0" description="暂无流量涨幅接口" />
    </div>

    <!-- 下钻抽屉 -->
    <el-drawer v-model="drawerVisible" :title="drawerTitle" size="70%" direction="rtl" destroy-on-close>
      <div class="drill-content">
        <div class="trace-list-section">
          <div class="section-title">最近链路 <span class="hint">点击链路查看调用链详情</span></div>
          <el-table
            v-loading="traceListLoading"
            :data="traceList"
            size="small"
            stripe
            @row-click="openTraceChain"
            row-class-name="clickable-row"
          >
            <el-table-column prop="traceId" label="TraceId" width="280" show-overflow-tooltip />
            <el-table-column prop="duration" label="耗时" width="100" align="right">
              <template #default="{ row }">
                <span :class="row.duration > 1000 ? 'time-slow' : 'time-normal'">{{ row.duration }} ms</span>
              </template>
            </el-table-column>
            <el-table-column prop="serviceName" label="服务" width="180" show-overflow-tooltip />
            <el-table-column prop="operationName" label="操作" min-width="200" show-overflow-tooltip />
          </el-table>
        </div>

        <div v-if="traceChain" class="trace-chain-section">
          <div class="section-title">调用链详情</div>
          <div ref="chartRef" class="duration-chart"></div>
          <div class="chain-nodes">
            <div v-for="(node, idx) in traceChain.nodes" :key="idx" class="chain-node">
              <span class="node-index">{{ idx + 1 }}</span>
              <span class="node-name">{{ node.serviceName }} → {{ node.operationName }}</span>
              <span :class="node.duration > 1000 ? 'node-duration slow' : 'node-duration'">{{ node.duration }} ms</span>
            </div>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { getApiTrafficSurge } from '@/api/gateway.js'
import { searchTracesByApi, getTraceChain } from '@/api/trace-chain.js'
import * as echarts from 'echarts'

const loading = ref(false)
const compareMode = ref('day')
const tableData = ref([])
const lastUpdated = ref('')

const REFRESH_INTERVAL = 60000
let refreshTimer = null
let countdown = REFRESH_INTERVAL / 1000
const countdownText = ref(`${countdown}s`)

const criticalCount = computed(() => tableData.value.filter(r => r.degradationRate >= 200).length)
const warningCount = computed(() => tableData.value.filter(r => r.degradationRate >= 50 && r.degradationRate < 200).length)

const filterLevel = ref(null)
const filteredData = computed(() => {
  if (filterLevel.value === 'critical') return tableData.value.filter(r => r.degradationRate >= 200)
  if (filterLevel.value === 'warning') return tableData.value.filter(r => r.degradationRate >= 50)
  return tableData.value
})

const toggleFilter = (level) => {
  filterLevel.value = filterLevel.value === level ? null : level
}

const fetchData = async () => {
  loading.value = true
  try {
    const res = await getApiTrafficSurge({ compareMode: compareMode.value })
    tableData.value = res.data || []
    lastUpdated.value = new Date().toLocaleTimeString()
    countdown = REFRESH_INTERVAL / 1000
  } catch (error) {
    console.error('Fetch error:', error)
    tableData.value = []
  } finally {
    loading.value = false
  }
}

const handleModeChange = () => fetchData()

const getRankType = (rank) => {
  if (rank <= 3) return 'danger'
  if (rank <= 10) return 'warning'
  return 'info'
}

const surgeClass = (rate) => {
  if (rate >= 200) return 'surge-rate critical'
  if (rate >= 100) return 'surge-rate bad'
  if (rate >= 50) return 'surge-rate warning'
  return 'surge-rate normal'
}

// ===== 链路下钻 =====
const drawerVisible = ref(false)
const drawerTitle = ref('')
const traceListLoading = ref(false)
const traceList = ref([])
const traceChain = ref(null)
const chartRef = ref(null)
let chartInstance = null

const openDrillDown = async (row) => {
  drawerVisible.value = true
  drawerTitle.value = `流量暴涨 - ${row.apiPath}`
  traceListLoading.value = true
  traceChain.value = null
  try {
    const res = await searchTracesByApi(row.apiPath, '1h', 20)
    traceList.value = res.data || []
  } catch (error) {
    console.error('搜索链路失败:', error)
    traceList.value = []
  } finally {
    traceListLoading.value = false
  }
}

const openTraceChain = async (trace) => {
  try {
    const res = await getTraceChain(trace.traceId, '1h')
    traceChain.value = res.data || null
    await nextTick()
    if (chartRef.value && traceChain.value) {
      renderDurationChart(traceChain.value.nodes || [])
    }
  } catch (error) {
    console.error('获取调用链失败:', error)
  }
}

const renderDurationChart = (nodes) => {
  if (chartInstance) chartInstance.dispose()
  chartInstance = echarts.init(chartRef.value)
  chartInstance.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: nodes.map((_, i) => `#${i + 1}`) },
    yAxis: { type: 'value', name: 'ms' },
    series: [{
      type: 'bar',
      data: nodes.map(n => n.duration || 0),
      itemStyle: { color: (p) => p.value > 1000 ? '#f56c6c' : p.value > 500 ? '#e6a23c' : '#67c23a' }
    }]
  })
}

// ===== 自动刷新 =====
const startRefresh = () => {
  stopRefresh()
  refreshTimer = setInterval(() => {
    countdown--
    if (countdown <= 0) {
      fetchData()
    } else {
      countdownText.value = `${countdown}s`
    }
  }, 1000)
}

const stopRefresh = () => {
  if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null }
}

const handleVisibility = () => {
  if (!document.hidden) fetchData()
}

onMounted(() => {
  fetchData()
  startRefresh()
  document.addEventListener('visibilitychange', handleVisibility)
})

onBeforeUnmount(() => {
  stopRefresh()
  document.removeEventListener('visibilitychange', handleVisibility)
  if (chartInstance) chartInstance.dispose()
})
</script>

<style scoped>
.traffic-surge-page {
  padding: 16px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.refresh-info {
  font-size: 12px;
  color: #999;
}

/* 概览卡片 */
.surge-summary {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.summary-card {
  padding: 20px;
  border-radius: 8px;
  text-align: center;
  border: 1px solid #ebeef5;
  cursor: pointer;
  transition: all 0.2s;
}

.summary-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.summary-card.active {
  border-width: 2px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.15);
}

.summary-card.critical { background: #fef0f0; border-color: #f56c6c; }
.summary-card.warning { background: #fdf6ec; border-color: #e6a23c; }
.summary-card.normal { background: #f0f9eb; border-color: #67c23a; }

.summary-value {
  font-size: 32px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.summary-card.critical .summary-value { color: #f56c6c; }
.summary-card.warning .summary-value { color: #e6a23c; }
.summary-card.normal .summary-value { color: #67c23a; }

.summary-label {
  font-size: 13px;
  color: #666;
  margin-top: 4px;
}

/* 表格 */
.surge-table-section {
  margin-bottom: 20px;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
}

.table-hint {
  font-size: 12px;
  color: #999;
}

.surge-rate.critical { color: #f56c6c; font-weight: 700; }
.surge-rate.bad { color: #f56c6c; font-weight: 600; }
.surge-rate.warning { color: #e6a23c; }
.surge-rate.normal { color: #909399; }

.count-current { color: #f56c6c; font-weight: 600; font-variant-numeric: tabular-nums; }
.count-previous { color: #909399; font-variant-numeric: tabular-nums; }

.time-value { font-variant-numeric: tabular-nums; }
.time-value.muted { color: #909399; }
.time-slow { color: #f56c6c; font-weight: 600; }
.time-normal { color: #67c23a; }

:deep(.clickable-row) { cursor: pointer; }
:deep(.clickable-row:hover td) { background-color: #ecf5ff !important; }

/* 下钻抽屉 */
.drill-content {
  padding: 16px;
}

.trace-list-section, .trace-chain-section {
  margin-bottom: 24px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 8px;
}

.hint {
  font-size: 12px;
  color: #999;
  font-weight: normal;
}

.duration-chart {
  width: 100%;
  height: 200px;
  margin-bottom: 16px;
}

.chain-nodes {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.chain-node {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  background: #f5f7fa;
  border-radius: 4px;
}

.node-index {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #409eff;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  flex-shrink: 0;
}

.node-name {
  flex: 1;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-duration {
  font-variant-numeric: tabular-nums;
  color: #67c23a;
}

.node-duration.slow {
  color: #f56c6c;
  font-weight: 600;
}
</style>
