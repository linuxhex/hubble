<template>
  <div class="second-chart">
    <div class="dashboard-header">
      <div class="dashboard-title">
        <h2>秒级监控图</h2>
      </div>
      <div class="header-actions">
        <el-select v-model="selectedMetric" size="small" style="width: 150px">
          <el-option label="QPS" value="qps" />
          <el-option label="响应时间" value="responseTime" />
          <el-option label="错误率" value="errorRate" />
          <el-option label="吞吐量" value="throughput" />
        </el-select>
        <el-button size="small" @click="handleRefresh">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button size="small" @click="isPaused = !isPaused">
          <el-icon><VideoPlay v-if="isPaused" /><VideoPause v-else /></el-icon>
          {{ isPaused ? '继续' : '暂停' }}
        </el-button>
      </div>
    </div>

    <!-- 实时指标卡片 -->
    <div class="realtime-metrics">
      <div class="metric-card" v-for="metric in realtimeMetrics" :key="metric.key">
        <div class="metric-label">{{ metric.label }}</div>
        <div class="metric-value">{{ metric.value }}</div>
        <div class="metric-unit">{{ metric.unit }}</div>
      </div>
    </div>

    <!-- 秒级图表 -->
    <div class="chart-wrapper">
      <div class="chart-header">
        <h3>{{ getMetricName(selectedMetric) }} - 秒级监控</h3>
        <div class="chart-info">
          <span>数据更新间隔: 1秒</span>
          <span>显示时长: 最近60秒</span>
        </div>
      </div>
      <div ref="chartRef" class="chart-container"></div>
    </div>

    <!-- 数据表格 -->
    <div class="data-table">
      <div class="table-header">
        <h3>秒级数据明细</h3>
        <el-button size="small" @click="showTable = !showTable">
          {{ showTable ? '收起' : '展开' }}
        </el-button>
      </div>
      <el-table
        v-if="showTable"
        :data="tableData"
        size="small"
        max-height="300"
        style="width: 100%"
      >
        <el-table-column prop="time" label="时间" width="180" />
        <el-table-column prop="qps" label="QPS" width="100" />
        <el-table-column prop="responseTime" label="响应时间(ms)" width="120" />
        <el-table-column prop="errorRate" label="错误率(%)" width="100" />
        <el-table-column prop="throughput" label="吞吐量(MB/s)" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { Refresh, VideoPlay, VideoPause } from '@element-plus/icons-vue'
import * as echarts from 'echarts'

const selectedMetric = ref('qps')
const isPaused = ref(false)
const showTable = ref(false)
const chartRef = ref(null)
let chart = null
let updateTimer = null

const realtimeMetrics = ref([
  { key: 'qps', label: '当前QPS', value: '0', unit: 'req/s' },
  { key: 'responseTime', label: '平均响应时间', value: '0', unit: 'ms' },
  { key: 'errorRate', label: '错误率', value: '0', unit: '%' },
  { key: 'throughput', label: '吞吐量', value: '0', unit: 'MB/s' }
])

const tableData = ref([])
const chartData = ref({
  times: [],
  values: []
})

// 生成初始60秒数据
const generateInitialData = () => {
  const now = Date.now()
  const times = []
  const values = []
  
  for (let i = 59; i >= 0; i--) {
    const time = new Date(now - i * 1000)
    times.push(time.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }))
    values.push(Math.floor(Math.random() * 1000 + 500))
  }
  
  chartData.value = { times, values }
  updateTableData()
}

// 更新表格数据
const updateTableData = () => {
  const data = []
  for (let i = 0; i < chartData.value.times.length; i++) {
    data.push({
      time: chartData.value.times[i],
      qps: chartData.value.values[i],
      responseTime: Math.floor(chartData.value.values[i] * 0.1),
      errorRate: (Math.random() * 0.5).toFixed(2),
      throughput: (chartData.value.values[i] / 100).toFixed(2)
    })
  }
  tableData.value = data.reverse()
}

// 添加新数据点
const addDataPoint = () => {
  if (isPaused.value) return
  
  const now = new Date()
  const time = now.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  const value = Math.floor(Math.random() * 1000 + 500)
  
  chartData.value.times.push(time)
  chartData.value.values.push(value)
  
  // 保持最多60个数据点
  if (chartData.value.times.length > 60) {
    chartData.value.times.shift()
    chartData.value.values.shift()
  }
  
  updateChart()
  updateMetrics()
  updateTableData()
}

// 更新图表
const updateChart = () => {
  if (!chart) return
  
  chart.setOption({
    xAxis: {
      data: chartData.value.times
    },
    series: [{
      data: chartData.value.values
    }]
  })
}

// 更新实时指标
const updateMetrics = () => {
  if (chartData.value.values.length === 0) return
  
  const latestValue = chartData.value.values[chartData.value.values.length - 1]
  const avgValue = chartData.value.values.reduce((a, b) => a + b, 0) / chartData.value.values.length
  
  realtimeMetrics.value[0].value = latestValue.toLocaleString()
  realtimeMetrics.value[1].value = Math.floor(avgValue * 0.1).toLocaleString()
  realtimeMetrics.value[2].value = (Math.random() * 0.5).toFixed(2)
  realtimeMetrics.value[3].value = (avgValue / 100).toFixed(2)
}

// 获取指标名称
const getMetricName = (metric) => {
  const names = {
    qps: 'QPS',
    responseTime: '响应时间',
    errorRate: '错误率',
    throughput: '吞吐量'
  }
  return names[metric] || metric
}

// 初始化图表
const initChart = () => {
  if (!chartRef.value) return
  
  generateInitialData()
  
  chart = echarts.init(chartRef.value)
  
  const option = {
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'cross',
        label: {
          backgroundColor: '#6a7985'
        }
      }
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      top: '10%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: chartData.value.times,
      axisLabel: {
        fontSize: 10,
        rotate: 45
      }
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        fontSize: 10
      }
    },
    series: [{
      name: getMetricName(selectedMetric.value),
      type: 'line',
      smooth: true,
      data: chartData.value.values,
      itemStyle: {
        color: '#409EFF'
      },
      areaStyle: {
        color: {
          type: 'linear',
          x: 0,
          y: 0,
          x2: 0,
          y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(64,158,255,0.3)' },
            { offset: 1, color: 'rgba(64,158,255,0)' }
          ]
        }
      },
      animation: false
    }]
  }
  
  chart.setOption(option)
  
  // 每秒更新数据
  updateTimer = setInterval(() => {
    addDataPoint()
  }, 1000)
}

const handleRefresh = () => {
  generateInitialData()
  updateChart()
  updateMetrics()
}

watch(selectedMetric, () => {
  if (chart) {
    chart.setOption({
      series: [{
        name: getMetricName(selectedMetric.value)
      }]
    })
  }
})

onMounted(() => {
  initChart()
  window.addEventListener('resize', () => {
    chart?.resize()
  })
})

onUnmounted(() => {
  if (updateTimer) {
    clearInterval(updateTimer)
  }
  chart?.dispose()
  window.removeEventListener('resize', () => {})
})
</script>

<style scoped>
.second-chart {
  padding: 16px;
  height: 100%;
  background: #f5f7fa;
  overflow: auto;
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  background: white;
  padding: 16px;
  border-radius: 4px;
}

.dashboard-title h2 {
  font-size: 16px;
  font-weight: 600;
  color: #333;
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.realtime-metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.metric-card {
  background: white;
  padding: 16px;
  border-radius: 4px;
  text-align: center;
  border: 1px solid #f0f0f0;
}

.metric-label {
  font-size: 12px;
  color: #666;
  margin-bottom: 8px;
}

.metric-value {
  font-size: 28px;
  font-weight: bold;
  color: #333;
  margin-bottom: 4px;
}

.metric-unit {
  font-size: 12px;
  color: #999;
}

.chart-wrapper {
  background: white;
  border-radius: 4px;
  padding: 16px;
  margin-bottom: 16px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.chart-header h3 {
  font-size: 14px;
  font-weight: 500;
  color: #333;
  margin: 0;
}

.chart-info {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #666;
}

.chart-container {
  height: 400px;
  width: 100%;
}

.data-table {
  background: white;
  border-radius: 4px;
  padding: 16px;
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.table-header h3 {
  font-size: 14px;
  font-weight: 500;
  color: #333;
  margin: 0;
}
</style>

