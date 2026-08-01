<template>
  <div class="trend-dashboard">
    <div class="dashboard-header">
      <div class="dashboard-title">
        <h2>趋势大盘</h2>
      </div>
      <div class="header-actions">
        <el-select v-model="selectedTimeRange" size="small" style="width: 150px">
          <el-option label="最近1小时" value="1h" />
          <el-option label="最近6小时" value="6h" />
          <el-option label="最近24小时" value="24h" />
          <el-option label="最近7天" value="7d" />
        </el-select>
        <el-button size="small" @click="handleRefresh">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <!-- 指标概览卡片 -->
    <div class="overview-cards">
      <div class="overview-card" v-for="metric in metrics" :key="metric.key">
        <div class="card-title">{{ metric.name }}</div>
        <div class="card-value">{{ metric.value }}</div>
        <div class="card-trend" :class="metric.trend > 0 ? 'trend-up' : 'trend-down'">
          {{ metric.trend > 0 ? '↑' : '↓' }} {{ Math.abs(metric.trend) }}%
        </div>
      </div>
    </div>

    <!-- 趋势图表区域 -->
    <div class="charts-container">
      <div class="chart-item" v-for="chart in charts" :key="chart.id">
        <div class="chart-header">
          <h3>{{ chart.title }}</h3>
          <div class="chart-legend">
            <span
              v-for="series in chart.series"
              :key="series.name"
              class="legend-item"
            >
              <span class="dot" :class="series.color"></span>
              <span>{{ series.name }}</span>
            </span>
          </div>
        </div>
        <div :ref="el => setChartRef(el, chart.id)" class="chart-container"></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import * as echarts from 'echarts'

const selectedTimeRange = ref('24h')
const chartInstances = ref({})

const metrics = ref([
  { key: 'qps', name: 'QPS', value: '3,456', trend: 8.2 },
  { key: 'avgTime', name: '平均响应时间', value: '234ms', trend: -5.8 },
  { key: 'errorRate', name: '错误率', value: '0.15%', trend: 0.03 },
  { key: 'throughput', name: '吞吐量', value: '12.5 MB/s', trend: 12.5 }
])

const charts = ref([
  {
    id: 'qps-trend',
    title: 'QPS趋势',
    series: [
      { name: 'QPS', color: 'info' }
    ]
  },
  {
    id: 'response-time-trend',
    title: '响应时间趋势',
    series: [
      { name: '平均响应时间', color: 'warn' },
      { name: 'P95响应时间', color: 'error' }
    ]
  },
  {
    id: 'error-trend',
    title: '错误趋势',
    series: [
      { name: '错误数', color: 'error' }
    ]
  },
  {
    id: 'throughput-trend',
    title: '吞吐量趋势',
    series: [
      { name: '吞吐量', color: 'info' }
    ]
  }
])

// 生成时间序列数据
const generateTimeSeriesData = (hours = 24) => {
  const data = []
  const now = new Date()
  for (let i = hours - 1; i >= 0; i--) {
    const time = new Date(now.getTime() - i * 60 * 60 * 1000)
    data.push({
      time: time.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
      value: Math.floor(Math.random() * 1000 + 500)
    })
  }
  return data
}

// 设置图表引用
const setChartRef = (el, chartId) => {
  if (el && !chartInstances.value[chartId]) {
    nextTick(() => {
      initChart(el, chartId)
    })
  }
}

// 初始化图表
const initChart = (container, chartId) => {
  if (!container) return
  
  const chart = echarts.init(container)
  const data = generateTimeSeriesData(24)
  
  let option = {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' }
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
      data: data.map(d => d.time),
      axisLabel: { fontSize: 10, rotate: 45 }
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 10 }
    },
    series: []
  }

  // 根据图表ID设置不同的系列
  if (chartId === 'qps-trend') {
    option.series = [{
      name: 'QPS',
      type: 'line',
      smooth: true,
      data: data.map(d => d.value),
      itemStyle: { color: '#409EFF' },
      areaStyle: {
        color: {
          type: 'linear',
          x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(64,158,255,0.3)' },
            { offset: 1, color: 'rgba(64,158,255,0)' }
          ]
        }
      }
    }]
  } else if (chartId === 'response-time-trend') {
    option.series = [
      {
        name: '平均响应时间',
        type: 'line',
        smooth: true,
        data: data.map(d => d.value),
        itemStyle: { color: '#E6A23C' }
      },
      {
        name: 'P95响应时间',
        type: 'line',
        smooth: true,
        data: data.map(d => d.value * 1.5),
        itemStyle: { color: '#F56C6C' }
      }
    ]
  } else if (chartId === 'error-trend') {
    option.series = [{
      name: '错误数',
      type: 'bar',
      data: data.map(() => Math.floor(Math.random() * 50)),
      itemStyle: { color: '#F56C6C' }
    }]
  } else if (chartId === 'throughput-trend') {
    option.series = [{
      name: '吞吐量',
      type: 'line',
      smooth: true,
      data: data.map(d => d.value / 100),
      itemStyle: { color: '#409EFF' },
      areaStyle: {
        color: {
          type: 'linear',
          x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(64,158,255,0.3)' },
            { offset: 1, color: 'rgba(64,158,255,0)' }
          ]
        }
      }
    }]
  }

  chart.setOption(option)
  chartInstances.value[chartId] = chart
}

const handleRefresh = () => {
  // 重新生成数据并更新图表
  Object.keys(chartInstances.value).forEach(chartId => {
    const chart = chartInstances.value[chartId]
    if (chart) {
      const data = generateTimeSeriesData(24)
      // 更新图表数据
      chart.setOption({
        xAxis: { data: data.map(d => d.time) },
        series: chart.getOption().series.map((s, index) => ({
          ...s,
          data: index === 0 ? data.map(d => d.value) : data.map(d => d.value * 1.5)
        }))
      })
    }
  })
}

onMounted(() => {
  window.addEventListener('resize', () => {
    Object.values(chartInstances.value).forEach(chart => {
      chart?.resize()
    })
  })
})

onUnmounted(() => {
  Object.values(chartInstances.value).forEach(chart => {
    chart?.dispose()
  })
  window.removeEventListener('resize', () => {})
})
</script>

<style scoped>
.trend-dashboard {
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

.overview-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.overview-card {
  background: white;
  padding: 16px;
  border-radius: 4px;
  border: 1px solid #f0f0f0;
}

.card-title {
  font-size: 14px;
  color: #666;
  margin-bottom: 8px;
}

.card-value {
  font-size: 24px;
  font-weight: 600;
  color: #333;
  margin-bottom: 8px;
}

.card-trend {
  font-size: 12px;
}

.trend-up {
  color: #52c41a;
}

.trend-down {
  color: #ff4d4f;
}

.charts-container {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.chart-item {
  background: white;
  border-radius: 4px;
  padding: 16px;
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

.chart-legend {
  display: flex;
  gap: 16px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #666;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot.info {
  background-color: #409EFF;
}

.dot.warn {
  background-color: #E6A23C;
}

.dot.error {
  background-color: #F56C6C;
}

.chart-container {
  height: 250px;
  width: 100%;
}
</style>


