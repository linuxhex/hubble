<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { getApiDegradation, getP60Ranking } from '@/api/gateway.js'
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

const modeOptions = [
  { label: '今天 vs 昨天', value: 'day' },
  { label: '本周 vs 上周', value: 'week' },
  { label: 'P60 耗时排名', value: 'p60' }
]

const fetchData = async () => {
  loading.value = true
  try {
    let res
    if (compareMode.value === 'p60') {
      res = await getP60Ranking({ compareMode: 'day' })
    } else {
      res = await getApiDegradation({ compareMode: compareMode.value })
    }
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

const handleModeChange = (val) => {
  compareMode.value = val
  fetchData()
}

const getRankType = (rank) => {
  if (rank <= 3) return 'danger'
  if (rank <= 10) return 'warning'
  return 'info'
}

const formatRate = (rate) => `${Math.abs(rate).toFixed(1)}%`

const rateIcon = (rate) => {
  if (rate > 0) return '↑'
  if (rate < 0) return '↓'
  return '-'
}

const rateClass = (rate) => {
  if (rate > 0) return 'degradation-rate bad'
  if (rate < 0) return 'degradation-rate good'
  return 'degradation-rate neutral'
}

// ===== 链路下钻 =====
const drawerVisible = ref(false)
const drawerTitle = ref('')
const drawerApiPath = ref('')
const traceList = ref([])
const traceListLoading = ref(false)

const chainVisible = ref(false)
const chainLoading = ref(false)
const chainNodes = ref([])
const chainTraceId = ref('')
const chainTotalLogs = ref(0)
const selectedNode = ref(null)

const chartContainerRef = ref(null)
let durationChart = null

const totalDuration = () => chainNodes.value.reduce((sum, n) => sum + (n.duration || 0), 0)

const openDrillDown = async (row) => {
  drawerApiPath.value = row.apiPath
  drawerTitle.value = `接口链路下钻：${row.apiPath}`
  drawerVisible.value = true
  chainVisible.value = false
  selectedNode.value = null
  traceListLoading.value = true
  try {
    const res = await searchTracesByApi(row.apiPath, '1h', 20)
    traceList.value = res.data || []
  } catch (e) {
    console.error('搜索链路失败:', e)
    traceList.value = []
  } finally {
    traceListLoading.value = false
  }
}

const openTraceChain = async (trace) => {
  chainTraceId.value = trace.traceId
  chainVisible.value = true
  chainLoading.value = true
  selectedNode.value = null
  try {
    const res = await getTraceChain(trace.traceId, '1h')
    const data = res?.data || res
    chainNodes.value = data.nodes || []
    chainTotalLogs.value = data.totalLogs || 0
    await nextTick()
    renderDurationChart()
  } catch (e) {
    console.error('获取链路失败:', e)
    chainNodes.value = []
  } finally {
    chainLoading.value = false
  }
}

const renderDurationChart = () => {
  if (!chartContainerRef.value || chainNodes.value.length === 0) return
  if (durationChart) durationChart.dispose()
  durationChart = echarts.init(chartContainerRef.value)

  const services = chainNodes.value.map(n => n.serviceName)
  const durations = chainNodes.value.map(n => n.duration)
  const maxDuration = Math.max(...durations, 1)

  durationChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const p = params[0]
        return `${p.name}<br/>耗时: <b>${p.value}ms</b>`
      }
    },
    grid: { left: '3%', right: '8%', bottom: '3%', top: '10%', containLabel: true },
    xAxis: { type: 'value', name: '耗时(ms)', max: Math.ceil(maxDuration * 1.2) },
    yAxis: { type: 'category', data: services, inverse: true, axisLabel: { width: 100, overflow: 'truncate' } },
    series: [{
      type: 'bar',
      data: durations.map((d, i) => ({
        value: d,
        itemStyle: {
          color: d > 1000 ? '#f56c6c' : d > 500 ? '#e6a23c' : '#67c23a',
          borderRadius: [0, 4, 4, 0]
        },
        label: {
          show: true,
          position: 'right',
          formatter: `${d}ms`,
          fontSize: 11,
          color: '#333'
        }
      })),
      barWidth: 20
    }]
  })
}

const selectNode = (index) => {
  selectedNode.value = selectedNode.value === index ? null : index
}

const startAutoRefresh = () => {
  refreshTimer = setInterval(() => {
    countdown--
    if (countdown <= 0) fetchData()
    countdownText.value = `${Math.max(0, countdown)}s`
  }, 1000)
}

const handleVisibilityChange = () => {
  if (!document.hidden) fetchData()
}

onMounted(() => {
  fetchData()
  startAutoRefresh()
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  if (durationChart) durationChart.dispose()
})
</script>

<template>
  <div class="page-container">
    <div class="search-area">
      <el-form inline>
        <el-form-item label="对比模式" class="no-margin">
          <el-radio-group :model-value="compareMode" @update:model-value="handleModeChange">
            <el-radio-button v-for="opt in modeOptions" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item class="no-margin">
          <el-button type="primary" @click="fetchData" :loading="loading">刷新</el-button>
          <span class="update-info">上次更新: {{ lastUpdated }} | 下次刷新: {{ countdownText }}</span>
        </el-form-item>
      </el-form>
    </div>

    <div class="result-area">
      <div class="table-hint">点击行可下钻查看链路详情</div>
      <el-table
        v-loading="loading"
        :data="tableData"
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
        <el-table-column prop="apiPath" label="接口" min-width="200" show-overflow-tooltip />
        <el-table-column label="当前P60耗时" width="140" align="right">
          <template #default="{ row }">
            <span class="time-value">{{ row.currentAvgTime.toFixed(1) }} ms</span>
          </template>
        </el-table-column>
        <el-table-column label="上期P60耗时" width="140" align="right">
          <template #default="{ row }">
            <span class="time-value muted">{{ row.previousAvgTime.toFixed(1) }} ms</span>
          </template>
        </el-table-column>
        <el-table-column label="劣化幅度" width="140" align="center" prop="degradationRate">
          <template #default="{ row }">
            <span :class="rateClass(row.degradationRate)">
              {{ rateIcon(row.degradationRate) }} {{ formatRate(row.degradationRate) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="当前请求数" width="110" align="right" prop="currentCount" />
        <el-table-column label="上期请求数" width="110" align="right" prop="previousCount" />
        <el-table-column label="操作" width="100" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click.stop="openDrillDown(row)">下钻</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && tableData.length === 0" description="暂无劣化接口，表现良好" />
    </div>

    <!-- 下钻抽屉 -->
    <el-drawer v-model="drawerVisible" :title="drawerTitle" size="70%" direction="rtl" destroy-on-close>
      <div class="drill-content">
        <!-- 链路列表 -->
        <div class="trace-list-section">
          <div class="section-title">最近链路 <span class="hint">点击链路查看调用链详情</span></div>
          <el-table
            v-loading="traceListLoading"
            :data="traceList"
            size="small"
            stripe
            highlight-current-row
            @row-click="openTraceChain"
            row-class-name="clickable-row"
            max-height="240"
          >
            <el-table-column label="TraceID" width="200">
              <template #default="{ row }">
                <span class="trace-id">{{ row.traceId?.substring(0, 16) }}...</span>
              </template>
            </el-table-column>
            <el-table-column label="经过服务" prop="serviceName" min-width="200" show-overflow-tooltip />
            <el-table-column label="服务数" width="80" align="center" prop="serviceCount" />
            <el-table-column label="总耗时" width="100" align="right">
              <template #default="{ row }">
                <span :class="{'slow-time': row.duration > 1000}">{{ row.duration }}ms</span>
              </template>
            </el-table-column>
            <el-table-column label="日志数" width="80" align="center" prop="logCount" />
            <el-table-column label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="row.hasError ? 'danger' : 'success'" size="small">
                  {{ row.hasError ? '异常' : '正常' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="170" prop="formattedTime" />
          </el-table>
          <el-empty v-if="!traceListLoading && traceList.length === 0" description="未找到相关链路" :image-size="60" />
        </div>

        <!-- 链路详情 -->
        <div v-if="chainVisible" class="chain-detail-section">
          <div class="section-title">
            调用链详情
            <span class="hint">TraceID: {{ chainTraceId }} | 共 {{ chainTotalLogs }} 条日志 | {{ chainNodes.length }} 个服务 | 总耗时 {{ totalDuration() }}ms</span>
          </div>

          <div v-loading="chainLoading">
            <!-- 耗时柱状图 -->
            <div ref="chartContainerRef" class="duration-chart"></div>

            <!-- 链路流程图 -->
            <div class="chain-flow">
              <div
                v-for="(node, index) in chainNodes"
                :key="index"
                class="chain-node-wrapper"
              >
                <div
                  class="chain-node"
                  :class="{ 'node-error': node.status === 'error', 'node-active': selectedNode === index }"
                  @click="selectNode(index)"
                >
                  <div class="node-header">
                    <span class="node-service">{{ node.serviceName }}</span>
                    <span class="node-status" :class="node.status">{{ node.status === 'error' ? '异常' : '正常' }}</span>
                  </div>
                  <div class="node-path">{{ node.apiPath || '--' }}</div>
                  <div class="node-stats">
                    <div class="stat-item">
                      <span class="stat-label">耗时</span>
                      <span class="stat-value" :class="{'stat-slow': node.duration > 1000}">{{ node.duration }}ms</span>
                    </div>
                    <div class="stat-item">
                      <span class="stat-label">日志</span>
                      <span class="stat-value">{{ node.logCount }}</span>
                    </div>
                  </div>
                  <div class="node-meta">
                    <span class="node-time">{{ node.formattedTime }}</span>
                  </div>
                </div>
                <div v-if="index < chainNodes.length - 1" class="chain-arrow">
                  <div class="arrow-line"></div>
                  <div class="arrow-duration">{{ node.duration }}ms</div>
                </div>
              </div>
            </div>

            <!-- 节点日志详情 -->
            <div v-if="selectedNode !== null" class="node-detail">
              <div class="detail-header">
                <div class="detail-title-section">
                  <span class="detail-title">{{ chainNodes[selectedNode].serviceName }} - 日志详情</span>
                  <span class="detail-meta">{{ chainNodes[selectedNode].logCount }} 条日志 | 耗时 {{ chainNodes[selectedNode].duration }}ms</span>
                </div>
                <el-button size="small" @click="selectedNode = null">关闭</el-button>
              </div>
              <div class="detail-logs">
                <div v-for="(log, lIndex) in chainNodes[selectedNode].logs" :key="lIndex" class="log-item" :class="{'log-error': log.level === 'ERROR'}">
                  <div class="log-header">
                    <el-tag :type="log.level === 'ERROR' ? 'danger' : log.level === 'WARN' ? 'warning' : 'info'" size="small">
                      {{ log.level }}
                    </el-tag>
                    <span class="log-time">{{ log.formattedTime }}</span>
                  </div>
                  <div class="log-message">{{ log.message }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.page-container {
  padding: 0;
  height: 100%;
  background: #f5f7fa;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.search-area {
  background: #fff;
  border-radius: 4px;
  padding: 12px 24px;
}

.search-area .el-form-item.no-margin {
  margin-bottom: 0;
}

.update-info {
  font-size: 12px;
  color: #999;
  margin-left: 12px;
}

.result-area {
  background: #fff;
  border-radius: 4px;
  padding: 16px;
  flex: 1;
  overflow: auto;
}

.table-hint {
  font-size: 12px;
  color: #999;
  margin-bottom: 8px;
}

.time-value {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.time-value.muted {
  color: #909399;
}

.degradation-rate {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.degradation-rate.bad { color: #f56c6c; }
.degradation-rate.good { color: #67c23a; }
.degradation-rate.neutral { color: #909399; }

:deep(.clickable-row) { cursor: pointer; }
:deep(.clickable-row:hover td) { background-color: #ecf5ff !important; }

/* ===== 下钻抽屉 ===== */
.drill-content {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #333;
  margin-bottom: 10px;
}

.section-title .hint {
  font-size: 12px;
  font-weight: normal;
  color: #999;
  margin-left: 8px;
}

.trace-id {
  font-family: monospace;
  font-size: 12px;
  color: #1890ff;
}

.slow-time {
  color: #f56c6c;
  font-weight: 600;
}

/* ===== 链路详情 ===== */
.chain-detail-section {
  margin-top: 8px;
}

.duration-chart {
  width: 100%;
  height: 200px;
  margin-bottom: 16px;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
  padding: 8px;
}

.chain-flow {
  background: #fafafa;
  padding: 16px;
  border-radius: 4px;
  display: flex;
  align-items: flex-start;
  overflow-x: auto;
  gap: 0;
}

.chain-node-wrapper {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.chain-node {
  width: 170px;
  border: 2px solid #e4e7ed;
  border-radius: 8px;
  padding: 10px;
  cursor: pointer;
  transition: all 0.2s;
  background: white;
}

.chain-node:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.2);
}

.chain-node.node-active {
  border-color: #409eff;
  background: #ecf5ff;
}

.chain-node.node-error {
  border-color: #f56c6c;
  background: #fef0f0;
}

.node-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.node-service {
  font-size: 12px;
  font-weight: 600;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100px;
}

.node-status {
  font-size: 10px;
  padding: 2px 5px;
  border-radius: 10px;
  font-weight: 500;
}

.node-status.success { background: #f0f9eb; color: #67c23a; }
.node-status.error { background: #fef0f0; color: #f56c6c; }

.node-path {
  font-size: 10px;
  color: #1890ff;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-bottom: 6px;
}

.node-stats {
  display: flex;
  gap: 8px;
  margin-bottom: 4px;
  padding: 4px 0;
  border-top: 1px solid #f0f0f0;
  border-bottom: 1px solid #f0f0f0;
}

.stat-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat-label { font-size: 10px; color: #999; }
.stat-value { font-size: 12px; font-weight: 600; color: #333; }
.stat-value.stat-slow { color: #f56c6c; }

.node-meta {
  font-size: 10px;
  color: #999;
}

.chain-arrow {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 0 6px;
  min-width: 55px;
  justify-content: center;
}

.arrow-line {
  width: 35px;
  height: 2px;
  background: linear-gradient(to right, #dcdfe6, #c0c4cc);
  margin-bottom: 4px;
  position: relative;
}

.arrow-line::after {
  content: '';
  position: absolute;
  right: -2px;
  top: -3px;
  width: 0;
  height: 0;
  border-left: 6px solid #c0c4cc;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
}

.arrow-duration {
  font-size: 10px;
  color: #666;
  font-weight: 500;
  white-space: nowrap;
  background: white;
  padding: 1px 5px;
  border-radius: 10px;
  border: 1px solid #e4e7ed;
}

/* ===== 节点日志详情 ===== */
.node-detail {
  background: white;
  border-radius: 4px;
  margin-top: 16px;
  border: 1px solid #e4e7ed;
  overflow: hidden;
}

.detail-header {
  padding: 10px 16px;
  background: #fafafa;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-title-section {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.detail-title { font-size: 13px; font-weight: 600; color: #333; }
.detail-meta { font-size: 11px; color: #999; }

.detail-logs {
  padding: 12px 16px;
  max-height: 300px;
  overflow-y: auto;
}

.log-item {
  padding: 8px 10px;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
  margin-bottom: 6px;
}

.log-item:last-child { margin-bottom: 0; }

.log-item.log-error {
  border-color: #ffccc7;
  background: #fff2f0;
}

.log-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.log-time {
  font-size: 11px;
  color: #999;
  font-family: monospace;
}

.log-message {
  font-size: 12px;
  color: #333;
  line-height: 1.5;
  word-break: break-all;
  font-family: monospace;
  white-space: pre-wrap;
  max-height: 120px;
  overflow-y: auto;
}
</style>
