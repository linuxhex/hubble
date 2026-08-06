<template>
  <div class="alert-overview">
    <el-card>
      <!-- 操作栏 -->
      <div class="action-bar">
        <el-button type="primary" @click="handleRefresh" :loading="loading">
          <el-icon><Refresh /></el-icon>
          刷新 {{ countdown }}s
        </el-button>
      </div>

      <!-- 卡片网格 -->
      <div v-loading="loading" class="alert-cards-grid">
        <el-card
          v-for="config in configList"
          :key="config.id"
          class="alert-card"
          :body-style="{ padding: '16px' }"
        >
          <template #header>
            <div class="alert-card-header">
              <div class="alert-title-row">
                <span class="alert-title" :title="config.title">{{ config.title }}</span>
              </div>
              <el-button
                link
                type="primary"
                @click="handleViewDetail(config.id)"
                style="padding: 0"
              >
                详情
                <el-icon><ArrowRight /></el-icon>
              </el-button>
            </div>
          </template>

          <div class="alert-stats">
            <div class="stat-item">
              <div class="stat-label">Current</div>
              <div
                class="stat-value"
                :class="getStatValueClass(config.statistics?.currentLogCount || 0, config.alertThreshold)"
              >
                {{ config.statistics?.currentLogCount || 0 }}
              </div>
            </div>
            <div class="stat-item">
              <div class="stat-label">Max</div>
              <div
                class="stat-value"
                :class="getStatValueClass(config.statistics?.todayMax || 0, config.alertThreshold)"
              >
                {{ config.statistics?.todayMax || 0 }}
              </div>
            </div>
            <div class="stat-item">
              <div class="stat-label">Avg</div>
              <div
                class="stat-value"
                :class="getStatValueClass(config.statistics?.todayAvg || 0, config.alertThreshold)"
              >
                {{ (config.statistics?.todayAvg || 0).toFixed(1) }}
              </div>
            </div>
          </div>

          <div class="mini-chart-container">
            <div :ref="el => setChartRef(config.id, el)" class="mini-chart"></div>
          </div>
        </el-card>
      </div>

      <div class="pagination-container">
        <el-pagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.size"
          :page-sizes="[9, 12, 15, 18]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, onActivated, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, ArrowRight } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import {
  getAlertConfigList,
  getAlertDataList,
  getAlertStatistics
} from '@/api/alert.js'

const router = useRouter()

const loading = ref(false)
const configList = ref([])

const pagination = reactive({
  current: 1,
  size: 9,
  total: 0
})

const AUTO_REFRESH_INTERVAL = 60
const countdown = ref(AUTO_REFRESH_INTERVAL)
let autoRefreshTimer = null
let countdownTimer = null

const chartRefs = ref(new Map())
const charts = ref(new Map())

const setChartRef = (id, el) => {
  if (el) {
    chartRefs.value.set(id, el)
  }
}

const getStatValueClass = (value, threshold) => {
  if (value >= threshold) return 'stat-value-danger'
  if (value >= threshold * 0.5) return 'stat-value-warning'
  return 'stat-value-success'
}

const loadConfigList = async () => {
  loading.value = true
  try {
    const res = await getAlertConfigList({
      current: pagination.current,
      size: pagination.size,
      enabled: true
    })
    if (res.code === 200) {
      const list = res.data.records || res.data.list || []
      configList.value = list

      await Promise.all(
        list.map(async (config) => {
          try {
            const statsRes = await getAlertStatistics(config.id, '15m')
            if (statsRes.code === 200) {
              config.statistics = statsRes.data
            }
          } catch (error) {
            console.error(`加载配置 ${config.id} 统计数据失败:`, error)
          }
        })
      )

      pagination.total = res.data.total
      await nextTick()
      initCharts()
    }
  } catch (error) {
    console.error('加载配置列表失败:', error)
    ElMessage.error('加载配置列表失败')
  } finally {
    loading.value = false
  }
}

const initCharts = () => {
  charts.value.forEach(chart => chart.dispose())
  charts.value.clear()
  configList.value.forEach(config => {
    initChart(config.id)
  })
}

const initChart = async (configId) => {
  const container = chartRefs.value.get(configId)
  if (!container) return

  const existingChart = charts.value.get(configId)
  if (existingChart) {
    existingChart.dispose()
  }

  try {
    const config = configList.value.find(c => c.id === configId)
    const alertThreshold = config?.alertThreshold || 0

    const res = await getAlertDataList({
      alertConfigId: configId,
      timeRange: '15m',
      current: 1,
      size: 50
    })

    if (res.code === 200) {
      const data = res.data.records || res.data.list || []

      const chart = echarts.init(container)
      charts.value.set(configId, chart)

      const chartData = data
        .map(item => ({
          time: item.collectedAt ? new Date(item.collectedAt).toTimeString().substring(0, 8) : '',
          value: item.logCount
        }))
        .filter(d => d.time)
        .sort((a, b) => a.time.localeCompare(b.time))

      const option = {
        grid: {
          left: '30',
          right: '5',
          bottom: '20',
          top: '5',
          containLabel: false
        },
        xAxis: {
          type: 'category',
          show: true,
          data: chartData.map(d => d.time),
          axisLabel: {
            fontSize: 9,
            color: '#909399',
            interval: 'auto',
            rotate: 0,
            margin: 5
          },
          axisLine: { show: true, lineStyle: { color: '#DCDFE6' } },
          axisTick: { show: false }
        },
        yAxis: {
          type: 'value',
          show: true,
          axisLabel: { fontSize: 9, color: '#909399', margin: 3 },
          axisLine: { show: false },
          axisTick: { show: false },
          splitLine: {
            show: true,
            lineStyle: { color: '#EBEEF5', type: 'dashed', width: 1 }
          }
        },
        series: [
          {
            type: 'line',
            data: chartData.map(d => d.value),
            smooth: true,
            showSymbol: false,
            lineStyle: { width: 2, color: '#409EFF' },
            areaStyle: {
              color: {
                type: 'linear',
                x: 0, y: 0, x2: 0, y2: 1,
                colorStops: [
                  { offset: 0, color: 'rgba(64, 158, 255, 0.3)' },
                  { offset: 1, color: 'rgba(64, 158, 255, 0.05)' }
                ]
              }
            },
            markLine: {
              silent: true,
              symbol: 'none',
              label: { show: false },
              lineStyle: { color: '#F56C6C', type: 'solid', width: 2 },
              data: [{ yAxis: alertThreshold }]
            }
          }
        ]
      }

      chart.setOption(option)
      container.addEventListener('wheel', (e) => e.preventDefault(), { passive: false })
    }
  } catch (error) {
    console.error(`初始化图表失败: configId=${configId}`, error)
  }
}

const handleViewDetail = (configId) => {
  router.push({
    path: '/alert-dashboard',
    query: { configId: configId.toString() }
  })
}

const handleRefresh = () => {
  countdown.value = AUTO_REFRESH_INTERVAL
  loadConfigList()
}

const startAutoRefresh = () => {
  stopAutoRefresh()
  countdownTimer = window.setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) countdown.value = AUTO_REFRESH_INTERVAL
  }, 1000)
  autoRefreshTimer = window.setInterval(() => {
    loadConfigList()
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

const handleSizeChange = () => {
  pagination.current = 1
  loadConfigList()
}

const handleCurrentChange = () => {
  loadConfigList()
}

const handleResize = () => {
  charts.value.forEach(chart => chart.resize())
}

const handleVisibilityChange = () => {
  if (document.visibilityState === 'visible') {
    countdown.value = AUTO_REFRESH_INTERVAL
    loadConfigList()
  }
}

onMounted(() => {
  loadConfigList()
  window.addEventListener('resize', handleResize)
  document.addEventListener('visibilitychange', handleVisibilityChange)
  startAutoRefresh()
})

onActivated(() => {
  nextTick(() => initCharts())
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  stopAutoRefresh()
  charts.value.forEach(chart => chart.dispose())
})
</script>

<style scoped>
.alert-overview {
  padding: 0;
}

.alert-overview > .el-card {
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

.alert-cards-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  min-height: 400px;
}

.alert-card {
  transition: box-shadow 0.3s;
}

.alert-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.alert-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.alert-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.alert-title {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 200px;
}

.alert-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-bottom: 12px;
}

.stat-item {
  text-align: center;
}

.stat-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.stat-value {
  font-size: 18px;
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

.mini-chart-container {
  margin: 12px 0;
  height: 100px;
}

.mini-chart {
  width: 100%;
  height: 100%;
}

.pagination-container {
  display: flex;
  justify-content: center;
  margin-top: 16px;
  padding: 12px 0;
}
</style>
