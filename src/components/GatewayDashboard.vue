<template>
  <div class="gateway-dashboard">
    <div class="dashboard-header">
      <div class="dashboard-title">
        <h2>网关大盘</h2>
      </div>
    </div>
    
    <!-- 网关概览数据 -->
    <div class="overview-cards">
      <div class="overview-card">
        <div class="card-title">总请求量</div>
        <div class="card-value">1,234,567</div>
        <div class="card-trend up">
          <span>↑ 12.5%</span>
          <span class="trend-label">较上周期</span>
        </div>
      </div>
      <div class="overview-card">
        <div class="card-title">平均响应时间</div>
        <div class="card-value">234ms</div>
        <div class="card-trend down">
          <span>↓ 5.8%</span>
          <span class="trend-label">较上周期</span>
        </div>
      </div>
      <div class="overview-card">
        <div class="card-title">错误率</div>
        <div class="card-value">0.15%</div>
        <div class="card-trend up">
          <span>↑ 0.03%</span>
          <span class="trend-label">较上周期</span>
        </div>
      </div>
      <div class="overview-card">
        <div class="card-title">QPS</div>
        <div class="card-value">3,456</div>
        <div class="card-trend up">
          <span>↑ 8.2%</span>
          <span class="trend-label">较上周期</span>
        </div>
      </div>
    </div>

    <!-- 指标卡片区域 -->
    <div class="metric-cards">
      <!-- 现有的指标卡片 -->
    </div>

    <!-- 请求趋势图表 -->
    <div class="trend-chart">
      <div class="chart-header">
        <h3>请求趋势</h3>
        <div class="chart-legend">
          <span class="legend-item">
            <span class="dot info"></span>
            <span>INFO</span>
          </span>
          <span class="legend-item">
            <span class="dot warn"></span>
            <span>WARN</span>
          </span>
          <span class="legend-item">
            <span class="dot error"></span>
            <span>ERROR</span>
          </span>
        </div>
      </div>
      <div ref="chartRef" class="chart-container"></div>
    </div>

    <!-- 热门接口列表 -->
    <div class="hot-apis">
      <div class="section-title">热门接口</div>
      <el-table :data="hotApis" style="width: 100%">
        <el-table-column prop="path" label="接口路径" />
        <el-table-column prop="method" label="请求方法" width="100" />
        <el-table-column prop="qps" label="QPS" width="100" />
        <el-table-column prop="avgTime" label="平均响应时间" width="120" />
        <el-table-column prop="errorRate" label="错误率" width="100" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as echarts from 'echarts'

// 模拟热门接口数据
const hotApis = ref([
  {
    path: '/api/v1/users',
    method: 'GET',
    qps: 234,
    avgTime: '45ms',
    errorRate: '0.1%'
  },
  {
    path: '/api/v1/orders',
    method: 'POST',
    qps: 156,
    avgTime: '78ms',
    errorRate: '0.3%'
  },
  {
    path: '/api/v1/products',
    method: 'GET',
    qps: 123,
    avgTime: '56ms',
    errorRate: '0.2%'
  }
])

// 图表相关
const chartRef = ref(null)
let chart = null

// 生成假数据
const generateTrendData = () => {
  const now = new Date()
  const data = []
  for (let i = 0; i < 24; i++) {
    const time = new Date(now.getTime() - (23 - i) * 3600 * 1000)
    data.push({
      time: time.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
      info: Math.floor(Math.random() * 100 + 150),
      warn: Math.floor(Math.random() * 20 + 10),
      error: Math.floor(Math.random() * 10)
    })
  }
  return data
}

// 初始化图表
const initChart = () => {
  const trendData = generateTrendData()
  
  const option = {
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'shadow'
      }
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      top: '3%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: trendData.map(item => item.time),
      axisLabel: {
        interval: 2
      }
    },
    yAxis: {
      type: 'value'
    },
    series: [
      {
        name: 'INFO',
        type: 'line',
        smooth: true,
        data: trendData.map(item => item.info),
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
            colorStops: [{
              offset: 0,
              color: 'rgba(64,158,255,0.2)'
            }, {
              offset: 1,
              color: 'rgba(64,158,255,0)'
            }]
          }
        }
      },
      {
        name: 'WARN',
        type: 'line',
        smooth: true,
        data: trendData.map(item => item.warn),
        itemStyle: {
          color: '#E6A23C'
        },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [{
              offset: 0,
              color: 'rgba(230,162,60,0.2)'
            }, {
              offset: 1,
              color: 'rgba(230,162,60,0)'
            }]
          }
        }
      },
      {
        name: 'ERROR',
        type: 'line',
        smooth: true,
        data: trendData.map(item => item.error),
        itemStyle: {
          color: '#F56C6C'
        },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [{
              offset: 0,
              color: 'rgba(245,108,108,0.2)'
            }, {
              offset: 1,
              color: 'rgba(245,108,108,0)'
            }]
          }
        }
      }
    ]
  }

  chart = echarts.init(chartRef.value)
  chart.setOption(option)
}

onMounted(() => {
  initChart()
  window.addEventListener('resize', () => {
    chart?.resize()
  })
})
</script>

<style scoped>
.gateway-dashboard {
  background: white;
  padding: 16px;
  border-radius: 4px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.dashboard-title h2 {
  font-size: 16px;
  font-weight: 600;
  color: #333;
  margin: 0;
}

.overview-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.overview-card {
  background: #fafafa;
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
  display: flex;
  align-items: center;
  gap: 4px;
}

.card-trend.up {
  color: #52c41a;
}

.card-trend.down {
  color: #ff4d4f;
}

.trend-label {
  color: #999;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
  margin-bottom: 16px;
}

.trend-chart {
  background: white;
  border-radius: 4px;
  padding: 16px;
  margin-bottom: 16px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.chart-header h3 {
  font-size: 16px;
  margin: 0;
  font-weight: 500;
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
  height: 200px;
  width: 100%;
}

.hot-apis {
  margin-top: 16px;
}
</style> 