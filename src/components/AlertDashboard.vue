<template>
  <div class="alert-dashboard">
    <el-card body-style="padding: 16px">
      <div class="action-bar">
        <el-button type="success" @click="handleJumpToSls">
          <el-icon><Link /></el-icon>
          跳转至SLS
        </el-button>
        <el-button type="primary" @click="handleRefresh" :loading="loading">
          <el-icon><Refresh /></el-icon>
          刷新 {{ countdown }}s
        </el-button>
        <el-button type="info" @click="handleBackToOverview">
          <el-icon><ArrowLeft /></el-icon>
          返回
        </el-button>
      </div>

      <div v-if="selectedConfigId">
        <div class="time-range-selector-top">
          <div class="selector-label">时间范围：</div>
          <el-radio-group v-model="timeRange" @change="handleTimeRangeChange" size="default">
            <el-radio-button value="15m">最近15分钟</el-radio-button>
            <el-radio-button value="30m">最近半小时</el-radio-button>
            <el-radio-button value="1h">最近1小时</el-radio-button>
            <el-radio-button value="6h">最近6小时</el-radio-button>
            <el-radio-button value="1d">最近1天</el-radio-button>
          </el-radio-group>
        </div>

        <el-row :gutter="12" class="statistics-cards">
          <el-col :span="8">
            <el-card class="stat-card">
              <div
                class="stat-value"
                :class="getStatValueClass(statistics.currentLogCount, statistics.alertThreshold)"
              >
                {{ statistics.currentLogCount }}
              </div>
              <div class="stat-label">Current</div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card class="stat-card">
              <div
                class="stat-value"
                :class="getStatValueClass(statistics.todayMax, statistics.alertThreshold)"
              >
                {{ statistics.todayMax }}
              </div>
              <div class="stat-label">Max</div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card class="stat-card">
              <div
                class="stat-value"
                :class="getStatValueClass(statistics.todayAvg, statistics.alertThreshold)"
              >
                {{ statistics.todayAvg.toFixed(1) }}
              </div>
              <div class="stat-label">Avg</div>
            </el-card>
          </el-col>
        </el-row>

        <el-card class="chart-card">
          <template #header>
            <span>整体趋势</span>
          </template>
          <div ref="chartRef" style="width: 100%; height: 320px"></div>
        </el-card>

        <el-row :gutter="12" style="margin-top: 12px;">
          <el-col :span="12">
            <ErrorCountTrendChart
              v-if="selectedConfigId"
              :key="chartKey"
              :config-id="selectedConfigId"
              :time-range="timeRange"
            />
          </el-col>
          <el-col :span="12">
            <ErrorChangeRateChart
              v-if="selectedConfigId"
              :key="chartKey"
              :config-id="selectedConfigId"
              :time-range="timeRange"
            />
          </el-col>
        </el-row>

        <div class="error-type-ranking" v-loading="errorTypesLoading" style="margin-top: 12px;">
          <el-card header="错误类型排行榜 Top 10">
            <el-table :data="errorTypes" stripe>
              <el-table-column label="排名" width="80">
                <template #default="{ $index }">
                  <el-tag :type="getRankTagType($index)">
                    {{ $index + 1 }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="typeName" label="错误类型" min-width="200">
                <template #default="{ row }">
                  <div class="error-type-name">
                    <el-tag size="small" :type="getCategoryTagType(row.category)">
                      {{ getCategoryLabel(row.category) }}
                    </el-tag>
                    <span>{{ row.typeName }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="count" label="数量" width="100" sortable />
              <el-table-column prop="percentage" label="占比" width="100">
                <template #default="{ row }">
                  {{ (row.percentage != null ? row.percentage : 0).toFixed(2) }}%
                </template>
              </el-table-column>
              <el-table-column prop="growthRate" label="环比" width="120">
                <template #default="{ row }">
                  <span :class="getGrowthRateClass(row.growthRate)">
                    {{ row.growthRate > 0 ? '↑' : row.growthRate < 0 ? '↓' : '-' }}
                    {{ Math.abs(row.growthRate || 0).toFixed(2) }}%
                  </span>
                </template>
              </el-table-column>
              <el-table-column prop="firstSeenAt" label="首次出现" width="180">
                <template #default="{ row }">
                  {{ formatDateTime(row.firstSeenAt) }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" fixed="right">
                <template #default="{ row }">
                  <span class="action-link view-link" @click="viewSampleLog(row)">查看样本</span>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </div>

        <el-dialog v-model="sampleLogDialogVisible" title="错误样本日志" width="60%">
          <el-input
            type="textarea"
            :rows="10"
            :model-value="selectedSampleLog"
            readonly
          />
        </el-dialog>
      </div>

      <el-empty v-else description="未找到监控配置" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, onActivated } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Refresh, Link } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import {
  getAlertConfigList,
  getAlertStatistics,
  getAlertDataList
} from '@/api/alert.js'
import { getTopErrorTypes } from '@/api/errorAnalysis.js'
import { getSlsKeywordList } from '@/api/sls-keyword-management.js'
import ErrorCountTrendChart from '@/components/ErrorCountTrendChart.vue'
import ErrorChangeRateChart from '@/components/ErrorChangeRateChart.vue'
import { generateSlsLink } from '@/utils/sls.js'
import { nowInSeconds } from '@/utils/timestamp.js'

const route = useRoute()
const router = useRouter()

const configIdFromRoute = computed(() =>
  route.query.configId ? Number(route.query.configId) : null
)

const configList = ref([])
const selectedConfigId = ref(null)
const selectedConfig = ref(null)
const statistics = ref({
  currentLogCount: 0,
  alertThreshold: 0,
  todayMax: 0,
  todayAvg: 0,
  todayAlertCount: 0,
  detailStatistics: undefined
})
const timeRange = ref('1h')
const loading = ref(false)

const errorTypes = ref([])
const errorTypesLoading = ref(false)
const sampleLogDialogVisible = ref(false)
const selectedSampleLog = ref('')

const AUTO_REFRESH_INTERVAL = 60
const countdown = ref(AUTO_REFRESH_INTERVAL)
let autoRefreshTimer = null
let countdownTimer = null

const chartKey = ref(0)
const chartRef = ref(null)
let chart = null

const loadConfigList = async () => {
  try {
    const res = await getAlertConfigList({ current: 1, size: 1000 })
    if (res.code === 200) {
      configList.value = res.data.records || res.data.list || []

      if (configIdFromRoute.value) {
        selectedConfigId.value = configIdFromRoute.value
        const config = configList.value.find(c => c.id === configIdFromRoute.value)
        if (config) {
          selectedConfig.value = config
          handleConfigChange()
        } else {
          ElMessage.error('未找到该监控配置')
        }
      }
    }
  } catch (error) {
    console.error('加载配置列表失败:', error)
    ElMessage.error('加载配置列表失败')
  }
}

const handleBackToOverview = () => {
  router.push('/alert-overview')
}

const handleJumpToSls = async () => {
  if (!selectedConfig.value) {
    ElMessage.warning('未选择监控配置')
    return
  }
  try {
    const res = await getSlsKeywordList({ current: 1, size: 1000 })
    const keywordTemplate = (res.data.records || res.data.list || []).find(
      (item) => item.id === selectedConfig.value?.keywordTemplateId
    )
    if (!keywordTemplate) {
      ElMessage.error('未找到关键字模板信息')
      return
    }

    const endTime = nowInSeconds()
    let startTime
    switch (timeRange.value) {
      case '15m': startTime = endTime - 15 * 60; break
      case '30m': startTime = endTime - 30 * 60; break
      case '1h': startTime = endTime - 60 * 60; break
      case '6h': startTime = endTime - 6 * 60 * 60; break
      case '1d': startTime = endTime - 24 * 60 * 60; break
      default: startTime = endTime - 60 * 60
    }

    const slsLink = generateSlsLink('', {
      project: 'k8s-log-c7fd130d77f0f4627ac91c831bffeb751',
      logstore: keywordTemplate.logstore || 'all',
      queryString: keywordTemplate.keywords || '',
      startTime,
      endTime
    })

    if (slsLink) {
      window.open(slsLink, '_blank')
    } else {
      ElMessage.error('生成SLS链接失败')
    }
  } catch (error) {
    console.error('跳转SLS失败:', error)
    ElMessage.error('跳转SLS失败')
  }
}

const getStatValueClass = (value, threshold) => {
  if (value >= threshold) return 'stat-value-danger'
  if (value >= threshold * 0.5) return 'stat-value-warning'
  return 'stat-value-success'
}

const handleConfigChange = () => {
  if (selectedConfigId.value) {
    loadStatistics()
    initChart()
    fetchErrorTypes()
  }
}

const loadStatistics = async () => {
  if (!selectedConfigId.value) return
  try {
    const res = await getAlertStatistics(selectedConfigId.value, timeRange.value)
    if (res.code === 200) {
      statistics.value = res.data
    }
  } catch (error) {
    ElMessage.error('加载统计数据失败')
  }
}

const handleRefresh = () => {
  countdown.value = AUTO_REFRESH_INTERVAL
  chartKey.value++
  loadStatistics()
  updateChart()
  fetchErrorTypes()
}

const startAutoRefresh = () => {
  stopAutoRefresh()
  countdownTimer = window.setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) countdown.value = AUTO_REFRESH_INTERVAL
  }, 1000)
  autoRefreshTimer = window.setInterval(() => {
    chartKey.value++
    loadStatistics()
    updateChart()
    fetchErrorTypes()
  }, AUTO_REFRESH_INTERVAL * 1000)
}

const stopAutoRefresh = () => {
  if (autoRefreshTimer) {
    clearInterval(autoRefreshTimer)
    autoRefreshTimer = null
  }
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
}

const initChart = () => {
  setTimeout(() => {
    if (!chartRef.value) return
    if (chart) chart.dispose()
    chart = echarts.init(chartRef.value)
    updateChart()
  }, 100)
}

const formatTime = (dateTime, range) => {
  const date = new Date(dateTime)
  if (range === '15m' || range === '30m') return date.toTimeString().substring(0, 8)
  if (range === '6h') return date.toTimeString().substring(0, 5)
  return `${(date.getMonth() + 1).toString().padStart(2, '0')}-${date.getDate().toString().padStart(2, '0')} ${date.getHours().toString().padStart(2, '0')}:${date.getMinutes().toString().padStart(2, '0')}`
}

const updateChart = () => {
  if (!chart || !selectedConfigId.value) return

  getAlertDataList({
    alertConfigId: selectedConfigId.value,
    timeRange: timeRange.value,
    current: 1,
    size: 1000
  }).then((res) => {
    if (res.code === 200 && chart) {
      const allData = res.data.records || res.data.list || []

      const chartData = []
      allData.forEach((item) => {
        const time = formatTime(item.collectedAt, timeRange.value)
        const timestamp = new Date(item.collectedAt).getTime()
        chartData.push({ time, value: item.logCount, timestamp })
      })
      chartData.sort((a, b) => a.timestamp - b.timestamp)

      const option = {
        tooltip: {
          trigger: 'axis',
          formatter: (params) => {
            let result = params[0].axisValue + '<br/>'
            params.forEach((param) => {
              result += `${param.marker} ${param.seriesName}: ${param.value}<br/>`
            })
            return result
          }
        },
        legend: {
          data: ['日志数量', '告警阈值'],
          top: 10,
          left: 'center'
        },
        grid: {
          left: '3%',
          right: '4%',
          bottom: '3%',
          top: 80,
          containLabel: true
        },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: [...new Set(chartData.map(d => d.time))],
          axisLabel: { rotate: 45, interval: 'auto' }
        },
        yAxis: { type: 'value', name: '监控数量' },
        series: [
          {
            name: '日志数量',
            type: 'line',
            data: chartData.map(d => d.value),
            smooth: true,
            lineStyle: { width: 3 }
          },
          {
            name: '告警阈值',
            type: 'line',
            data: new Array(chartData.length).fill(statistics.value.alertThreshold),
            lineStyle: { color: '#f56c6c', width: 2, type: 'dashed' },
            itemStyle: { color: '#f56c6c' },
            symbol: 'none'
          }
        ]
      }
      chart.setOption(option)
      chartRef.value?.addEventListener('wheel', (e) => e.preventDefault(), { passive: false })
    }
  }).catch((error) => {
    console.error('更新图表失败:', error)
  })
}

const handleTimeRangeChange = () => {
  loadStatistics()
  updateChart()
  fetchErrorTypes()
}

const handleResize = () => {
  chart?.resize()
}

const handleVisibilityChange = () => {
  if (document.visibilityState === 'visible') {
    countdown.value = AUTO_REFRESH_INTERVAL
    loadStatistics()
    updateChart()
  }
}

const fetchErrorTypes = async () => {
  if (!selectedConfigId.value) return
  errorTypesLoading.value = true
  try {
    const result = await getTopErrorTypes({
      alertConfigId: selectedConfigId.value,
      timeRange: timeRange.value,
      limit: 10
    })
    errorTypes.value = result.data || []
  } catch (error) {
    console.error('获取错误类型失败:', error)
    ElMessage.error('获取错误类型失败')
  } finally {
    errorTypesLoading.value = false
  }
}

const viewSampleLog = (row) => {
  selectedSampleLog.value = row.sampleLog || '暂无样本'
  sampleLogDialogVisible.value = true
}

const getRankTagType = (index) => {
  if (index === 0) return 'danger'
  if (index === 1) return 'warning'
  if (index === 2) return 'success'
  return 'info'
}

const getCategoryTagType = (category) => {
  switch (category) {
    case 'rule': return 'primary'
    case 'other': return 'info'
    case 'ai': return 'warning'
    default: return ''
  }
}

const getCategoryLabel = (category) => {
  switch (category) {
    case 'rule': return '规则'
    case 'other': return '其它'
    case 'ai': return 'AI'
    default: return category || ''
  }
}

const getGrowthRateClass = (rate) => {
  if (rate > 50) return 'growth-critical'
  if (rate > 20) return 'growth-warning'
  if (rate < -20) return 'growth-good'
  return ''
}

const formatDateTime = (dateStr) => {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('zh-CN')
}

watch([selectedConfigId, timeRange], () => {
  fetchErrorTypes()
})

watch(
  () => route.query.configId,
  (newConfigId) => {
    if (newConfigId && configList.value.length > 0) {
      const configId = Number(newConfigId)
      const config = configList.value.find(c => c.id === configId)
      if (config) {
        selectedConfig.value = config
        selectedConfigId.value = configId
        handleConfigChange()
      } else {
        loadConfigList()
      }
    }
  }
)

onMounted(() => {
  loadConfigList()
  window.addEventListener('resize', handleResize)
  document.addEventListener('visibilitychange', handleVisibilityChange)
  startAutoRefresh()
})

onActivated(() => {
  setTimeout(() => {
    if (chartRef.value) {
      if (chart) chart.dispose()
      chart = echarts.init(chartRef.value)
      updateChart()
    }
  }, 100)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  stopAutoRefresh()
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>

<style scoped>
.alert-dashboard {
  padding: 0;
}

.alert-dashboard > .el-card {
  border-radius: 4px;
  border: 1px solid #e4e7ed;
}

.action-bar {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  padding-bottom: 16px;
  border-bottom: 1px solid #e4e7ed;
  margin-bottom: 16px;
}

.action-bar .el-button {
  display: flex;
  align-items: center;
  gap: 4px;
}

.statistics-cards {
  margin-bottom: 12px;
}

.stat-card {
  text-align: center;
}

.stat-card :deep(.el-card__body) {
  padding: 16px 12px !important;
}

.stat-value {
  font-size: 24px;
  font-weight: bold;
  transition: color 0.3s ease;
}

.stat-value-success {
  color: #67C23A;
}

.stat-value-warning {
  color: #E6A23C;
}

.stat-value-danger {
  color: #F56C6C;
}

.stat-label {
  margin-top: 6px;
  font-size: 13px;
  color: #666;
}

.time-range-selector-top {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 8px;
}

.selector-label {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
}

.chart-card {
  margin-bottom: 12px;
}

.chart-card :deep(.el-card__header) {
  padding: 12px 16px !important;
}

.chart-card :deep(.el-card__body) {
  padding: 12px !important;
}

.error-type-ranking :deep(.el-card__header) {
  padding: 12px 16px !important;
}

.error-type-ranking :deep(.el-card__body) {
  padding: 12px !important;
}

.error-type-ranking .error-type-name {
  display: flex;
  align-items: center;
  gap: 8px;
}

.error-type-ranking .growth-critical {
  color: #f56c6c;
  font-weight: bold;
}

.error-type-ranking .growth-warning {
  color: #e6a23c;
}

.error-type-ranking .growth-good {
  color: #67c23a;
}

.action-link {
  cursor: pointer;
  margin-right: 12px;
  font-size: 12px;
  transition: all 0.2s ease;
}

.view-link {
  color: #67c23a;
}

.view-link:hover {
  color: #059669;
  text-decoration: underline;
}
</style>
