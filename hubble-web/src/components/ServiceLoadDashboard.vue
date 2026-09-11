<template>
  <div class="service-load-page">
    <div class="page-header">
      <h2>服务负载</h2>
      <el-button size="small" @click="fetchData" :loading="loading">刷新</el-button>
    </div>

    <!-- 筛选区 -->
    <div class="filter-bar">
      <el-select v-model="timeRange" placeholder="时间范围" size="small" style="width: 140px" @change="fetchData">
        <el-option label="最近7天" value="7" />
        <el-option label="最近30天" value="30" />
        <el-option label="最近90天" value="90" />
        <el-option label="最近180天" value="180" />
      </el-select>
      <el-select v-model="selectedApps" multiple placeholder="选择服务（可多选）" size="small" style="width: 400px; margin-left: 12px" @change="fetchData">
        <el-option v-for="app in appList" :key="app" :label="app" :value="app" />
      </el-select>
      <div class="threshold-info">
        <span class="threshold-item"><span class="dot yellow"></span>CPU ≥ 80% 建议扩容</span>
        <span class="threshold-item"><span class="dot red"></span>CPU ≥ 90% 紧急扩容</span>
        <span class="threshold-item"><span class="dot yellow"></span>内存 ≥ 85% 建议扩容</span>
        <span class="threshold-item"><span class="dot red"></span>内存 ≥ 95% 紧急扩容</span>
      </div>
    </div>

    <!-- 对比图表区 -->
    <div class="chart-section" v-if="selectedApps.length > 0">
      <div class="section-title">CPU 使用率对比（%）</div>
      <div ref="cpuChartRef" class="chart-container"></div>
    </div>

    <div class="chart-section" v-if="selectedApps.length > 0">
      <div class="section-title">内存使用率对比（%）</div>
      <div ref="memoryChartRef" class="chart-container"></div>
    </div>

    <div class="chart-section" v-if="selectedApps.length > 0">
      <div class="section-title">最高 QPS 对比</div>
      <div ref="qpsChartRef" class="chart-container"></div>
    </div>

    <!-- 扩容评估表 -->
    <div class="assessment-section" v-if="assessmentData.length > 0">
      <div class="section-title">扩容评估（结合业务增长趋势）</div>
      <el-table :data="assessmentData" stripe border size="small" style="width: 100%">
        <el-table-column prop="appName" label="服务" width="180" />
        <el-table-column label="CPU 峰值" width="120" align="right">
          <template #default="{ row }">
            <span :class="getCpuClass(row.maxCpu)">{{ formatNum(row.maxCpu) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="CPU 增长" width="110" align="right">
          <template #default="{ row }">
            <span :class="row.cpuGrowth >= 30 ? 'metric-warning' : row.cpuGrowth >= 0 ? 'metric-normal' : 'metric-normal'">
              {{ row.cpuGrowth >= 0 ? '+' : '' }}{{ row.cpuGrowth }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column label="内存峰值" width="120" align="right">
          <template #default="{ row }">
            <span :class="getMemoryClass(row.maxMemory)">{{ formatNum(row.maxMemory) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="QPS 峰值" width="120" align="right">
          <template #default="{ row }">{{ formatNum(row.maxQps) }}</template>
        </el-table-column>
        <el-table-column label="QPS 增长" width="110" align="right">
          <template #default="{ row }">
            <span :class="row.qpsGrowth >= 30 ? 'metric-warning' : 'metric-normal'">
              {{ row.qpsGrowth >= 0 ? '+' : '' }}{{ row.qpsGrowth }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column label="平均 RT" width="100" align="right">
          <template #default="{ row }">{{ formatNum(row.avgRt) }}ms</template>
        </el-table-column>
        <el-table-column label="扩容建议" width="140" align="center">
          <template #default="{ row }">
            <el-tag :type="getAssessmentType(row)" size="small">{{ getAssessmentText(row) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 空状态 -->
    <div v-if="!loading && appList.length === 0" class="empty-state">
      <el-empty description="暂无数据，请先采集数据" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getServiceLoadApps, getServiceLoadTrend } from '@/api/service-load.js'

const timeRange = ref('30')
const selectedApps = ref([])
const appList = ref([])
const allData = ref({})
const loading = ref(false)

const cpuChartRef = ref(null)
const memoryChartRef = ref(null)
const qpsChartRef = ref(null)

let cpuChart = null
let memoryChart = null
let qpsChart = null

const formatNum = (val) => {
  if (val === null || val === undefined) return '-'
  return Number(val).toFixed(1)
}

const getCpuClass = (val) => {
  if (val >= 90) return 'metric-critical'
  if (val >= 80) return 'metric-warning'
  return 'metric-normal'
}

const getMemoryClass = (val) => {
  if (val >= 95) return 'metric-critical'
  if (val >= 85) return 'metric-warning'
  return 'metric-normal'
}

const getAssessmentType = (row) => {
  // 紧急扩容：资源峰值过高或业务增长过快
  if (row.maxCpu >= 90 || row.maxMemory >= 95) return 'danger'
  if (row.qpsGrowth >= 50 || row.cpuGrowth >= 50) return 'danger'
  
  // 建议扩容：资源接近阈值或业务有明显增长
  if (row.maxCpu >= 80 || row.maxMemory >= 85) return 'warning'
  if (row.qpsGrowth >= 30 || row.cpuGrowth >= 30) return 'warning'
  
  return 'success'
}

const getAssessmentText = (row) => {
  // 紧急扩容
  if (row.maxCpu >= 90 || row.maxMemory >= 95) return '紧急扩容'
  if (row.qpsGrowth >= 50) return '业务激增'
  if (row.cpuGrowth >= 50) return '负载激增'
  
  // 建议扩容
  if (row.maxCpu >= 80 || row.maxMemory >= 85) return '建议扩容'
  if (row.qpsGrowth >= 30) return '业务增长'
  if (row.cpuGrowth >= 30) return '负载上升'
  
  return '正常'
}

const assessmentData = ref([])

const fetchApps = async () => {
  try {
    const res = await getServiceLoadApps()
    if (res.code === 200 && res.data && res.data.length > 0) {
      appList.value = res.data
      // 默认选中第一个服务
      selectedApps.value = [res.data[0]]
      // 加载数据
      await fetchData()
    }
  } catch (e) {
    console.error('获取应用列表失败:', e)
  }
}

const fetchData = async () => {
  if (selectedApps.value.length === 0) {
    assessmentData.value = []
    return
  }

  loading.value = true
  const startDate = new Date()
  startDate.setDate(startDate.getDate() - parseInt(timeRange.value))
  const startDateStr = startDate.toISOString().split('T')[0]

  try {
    allData.value = {}
    for (const app of selectedApps.value) {
      const res = await getServiceLoadTrend(app, startDateStr)
      if (res.code === 200 && res.data) {
        allData.value[app] = res.data
      }
    }
    await nextTick()
    renderCharts()
    buildAssessment()
  } catch (e) {
    console.error('获取数据失败:', e)
  } finally {
    loading.value = false
  }
}

const buildAssessment = () => {
  assessmentData.value = selectedApps.value.map(app => {
    const data = allData.value[app] || []
    if (data.length === 0) {
      return { appName: app, maxCpu: 0, maxMemory: 0, maxQps: 0, avgRt: 0, gcCount: 0, qpsGrowth: 0, cpuGrowth: 0 }
    }
    
    // 计算峰值
    const maxCpu = Math.max(...data.map(d => d.maxCpu || 0))
    const maxMemory = Math.max(...data.map(d => d.maxMemory || 0))
    const maxQps = Math.max(...data.map(d => d.maxQps || 0))
    const avgRt = data.reduce((sum, d) => sum + (d.avgRt || 0), 0) / data.length
    const gcCount = data.reduce((sum, d) => sum + (d.gcCount || 0), 0)
    
    // 计算业务增长趋势（对比最近7天与前7天）
    const sortedData = [...data].sort((a, b) => new Date(a.statDate) - new Date(b.statDate))
    const recentDays = sortedData.slice(-7) // 最近7天
    const previousDays = sortedData.slice(-14, -7) // 前7天
    
    let qpsGrowth = 0
    let cpuGrowth = 0
    
    if (previousDays.length > 0 && recentDays.length > 0) {
      const prevAvgQps = previousDays.reduce((sum, d) => sum + (d.maxQps || 0), 0) / previousDays.length
      const recentAvgQps = recentDays.reduce((sum, d) => sum + (d.maxQps || 0), 0) / recentDays.length
      qpsGrowth = prevAvgQps > 0 ? ((recentAvgQps - prevAvgQps) / prevAvgQps * 100) : 0
      
      const prevAvgCpu = previousDays.reduce((sum, d) => sum + (d.maxCpu || 0), 0) / previousDays.length
      const recentAvgCpu = recentDays.reduce((sum, d) => sum + (d.maxCpu || 0), 0) / recentDays.length
      cpuGrowth = prevAvgCpu > 0 ? ((recentAvgCpu - prevAvgCpu) / prevAvgCpu * 100) : 0
    }
    
    return {
      appName: app,
      maxCpu,
      maxMemory,
      maxQps,
      avgRt,
      gcCount,
      qpsGrowth: Math.round(qpsGrowth * 10) / 10,
      cpuGrowth: Math.round(cpuGrowth * 10) / 10
    }
  })
}

const renderCharts = () => {
  const apps = selectedApps.value
  if (apps.length === 0) return

  // 获取所有日期（取第一个应用的日期作为基准）
  const firstAppData = allData.value[apps[0]] || []
  const dates = firstAppData.map(d => d.statDate)

  // CPU 图表
  if (cpuChartRef.value) {
    if (!cpuChart) cpuChart = echarts.init(cpuChartRef.value)
    const series = apps.map((app, idx) => ({
      name: app,
      type: 'line',
      smooth: true,
      data: (allData.value[app] || []).map(d => d.maxCpu),
      lineStyle: { width: 2 },
      markLine: idx === 0 ? {
        silent: true,
        data: [
          { yAxis: 80, lineStyle: { color: '#e6a23c', type: 'dashed' }, label: { formatter: '80%' } },
          { yAxis: 90, lineStyle: { color: '#f56c6c', type: 'dashed' }, label: { formatter: '90%' } }
        ]
      } : undefined
    }))
    cpuChart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: apps, bottom: 0, type: 'scroll' },
      grid: { left: 50, right: 20, top: 20, bottom: 50 },
      xAxis: { type: 'category', data: dates, axisLabel: { rotate: 45, fontSize: 10 } },
      yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
      series
    }, true)
  }

  // 内存图表
  if (memoryChartRef.value) {
    if (!memoryChart) memoryChart = echarts.init(memoryChartRef.value)
    const series = apps.map((app, idx) => ({
      name: app,
      type: 'line',
      smooth: true,
      data: (allData.value[app] || []).map(d => d.maxMemory),
      lineStyle: { width: 2 },
      markLine: idx === 0 ? {
        silent: true,
        data: [
          { yAxis: 85, lineStyle: { color: '#e6a23c', type: 'dashed' }, label: { formatter: '85%' } },
          { yAxis: 95, lineStyle: { color: '#f56c6c', type: 'dashed' }, label: { formatter: '95%' } }
        ]
      } : undefined
    }))
    memoryChart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: apps, bottom: 0, type: 'scroll' },
      grid: { left: 50, right: 20, top: 20, bottom: 50 },
      xAxis: { type: 'category', data: dates, axisLabel: { rotate: 45, fontSize: 10 } },
      yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
      series
    }, true)
  }

  // QPS 图表
  if (qpsChartRef.value) {
    if (!qpsChart) qpsChart = echarts.init(qpsChartRef.value)
    const series = apps.map(app => ({
      name: app,
      type: 'line',
      smooth: true,
      data: (allData.value[app] || []).map(d => d.maxQps),
      lineStyle: { width: 2 }
    }))
    qpsChart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: apps, bottom: 0, type: 'scroll' },
      grid: { left: 60, right: 20, top: 20, bottom: 50 },
      xAxis: { type: 'category', data: dates, axisLabel: { rotate: 45, fontSize: 10 } },
      yAxis: { type: 'value' },
      series
    }, true)
  }
}

const handleResize = () => {
  cpuChart?.resize()
  memoryChart?.resize()
  qpsChart?.resize()
}

onMounted(async () => {
  await fetchApps()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  cpuChart?.dispose()
  memoryChart?.dispose()
  qpsChart?.dispose()
})
</script>

<style scoped>
.service-load-page {
  padding: 16px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  background: #f5f7fa;
  overflow-y: auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: white;
  padding: 12px 20px;
  border-radius: 4px;
}

.page-header h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
  color: #303133;
}

.filter-bar {
  background: white;
  padding: 12px 20px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.threshold-info {
  margin-left: auto;
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #606266;
}

.threshold-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot.yellow {
  background: #e6a23c;
}

.dot.red {
  background: #f56c6c;
}

.chart-section {
  background: white;
  border-radius: 4px;
  padding: 16px;
}

.section-title {
  font-size: 13px;
  font-weight: 500;
  color: #606266;
  margin-bottom: 12px;
}

.chart-container {
  height: 260px;
}

.assessment-section {
  background: white;
  border-radius: 4px;
  padding: 16px;
}

.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: white;
  border-radius: 4px;
  min-height: 300px;
}

.metric-critical {
  color: #f56c6c;
  font-weight: bold;
}

.metric-warning {
  color: #e6a23c;
  font-weight: 500;
}

.metric-normal {
  color: #67c23a;
}

:deep(.el-table) {
  font-size: 12px;
}

:deep(.el-table th) {
  background: #f5f7fa;
  color: #606266;
  font-weight: 500;
}
</style>
