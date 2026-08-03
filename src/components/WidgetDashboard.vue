<template>
  <div class="widget-dashboard">
    <div class="dashboard-header">
      <div class="dashboard-title">
        <h2>微控件大盘</h2>
      </div>
      <div class="header-actions">
        <el-button type="primary" size="small" @click="showAddWidgetDialog = true">
          <el-icon><Plus /></el-icon>
          添加控件
        </el-button>
        <el-button size="small" @click="handleResetLayout">
          <el-icon><Refresh /></el-icon>
          重置布局
        </el-button>
      </div>
    </div>

    <!-- 控件网格 -->
    <div class="widgets-grid" ref="gridContainer">
      <div
        v-for="(widget, index) in widgets"
        :key="widget.id"
        class="widget-item"
        :style="{
          gridColumn: `span ${widget.colSpan || 1}`,
          gridRow: `span ${widget.rowSpan || 1}`
        }"
      >
        <div class="widget-header">
          <div class="widget-title">
            <el-icon v-if="widget.icon === 'DataLine'"><DataLine /></el-icon>
            <el-icon v-else-if="widget.icon === 'Timer'"><Timer /></el-icon>
            <el-icon v-else-if="widget.icon === 'Monitor'"><Monitor /></el-icon>
            <el-icon v-else><DataLine /></el-icon>
            <span>{{ widget.title }}</span>
          </div>
          <div class="widget-actions">
            <el-button
              link
              type="primary"
              size="small"
              @click="handleEditWidget(widget)"
            >
              <el-icon><Edit /></el-icon>
            </el-button>
            <el-button
              link
              type="danger"
              size="small"
              @click="handleRemoveWidget(widget.id)"
            >
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
        </div>
        <div class="widget-content">
          <!-- 数值卡片 -->
          <div v-if="widget.component === 'NumberCard'" class="number-card-widget">
            <div class="number-value">{{ widget.data.value }}</div>
            <div class="number-trend" :class="widget.data.trend.startsWith('+') ? 'trend-up' : 'trend-down'">
              {{ widget.data.trend }}
            </div>
          </div>
          <!-- 折线图 -->
          <div 
            v-else-if="widget.component === 'LineChart'" 
            :ref="el => initWidgetChart(el, widget)"
            class="line-chart-widget"
          ></div>
        </div>
      </div>
    </div>

    <!-- 添加控件对话框 -->
    <el-dialog
      v-model="showAddWidgetDialog"
      title="添加监控控件"
      width="600px"
    >
      <el-form :model="newWidget" label-width="100px">
        <el-form-item label="控件类型">
          <el-select v-model="newWidget.type" placeholder="请选择控件类型" style="width: 100%">
            <el-option
              v-for="type in widgetTypes"
              :key="type.value"
              :label="type.label"
              :value="type.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="控件标题">
          <el-input v-model="newWidget.title" placeholder="请输入控件标题" />
        </el-form-item>
        <el-form-item label="列宽">
          <el-input-number v-model="newWidget.colSpan" :min="1" :max="4" />
        </el-form-item>
        <el-form-item label="行高">
          <el-input-number v-model="newWidget.rowSpan" :min="1" :max="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddWidgetDialog = false">取消</el-button>
        <el-button type="primary" @click="handleAddWidget">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, h } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Edit, Delete, DataLine, Monitor, Connection, Timer } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { getGatewayOverview, getGatewayTrend } from '@/api/gateway.js'

const widgetTypes = [
  { label: '数值卡片', value: 'number-card' },
  { label: '折线图', value: 'line-chart' },
  { label: '柱状图', value: 'bar-chart' },
  { label: '饼图', value: 'pie-chart' },
  { label: '表格', value: 'table' }
]

const showAddWidgetDialog = ref(false)
const newWidget = ref({ type: '', title: '', colSpan: 1, rowSpan: 1 })
const chartInstances = ref({})

const widgets = ref([
  { id: 1, type: 'number-card', title: '总请求数', icon: 'DataLine', colSpan: 1, rowSpan: 1, data: { value: '-', trend: '-' }, component: 'NumberCard' },
  { id: 2, type: 'number-card', title: '平均响应时间', icon: 'Timer', colSpan: 1, rowSpan: 1, data: { value: '-', trend: '-' }, component: 'NumberCard' },
  { id: 3, type: 'line-chart', title: '请求趋势', icon: 'Monitor', colSpan: 2, rowSpan: 2, data: [], component: 'LineChart', chartId: 'chart-3' },
  { id: 4, type: 'number-card', title: '错误率', icon: 'DataLine', colSpan: 1, rowSpan: 1, data: { value: '-', trend: '-' }, component: 'NumberCard' }
])

const formatTrend = (val) => {
  if (val == null) return '-'
  const sign = val >= 0 ? '+' : ''
  return sign + val.toFixed(1) + '%'
}

const loadDashboardData = async () => {
  try {
    const [overviewRes, trendRes] = await Promise.all([
      getGatewayOverview(),
      getGatewayTrend()
    ])
    const overview = overviewRes.data || overviewRes
    const trend = trendRes.data || trendRes

    const numberCards = widgets.value.filter(w => w.component === 'NumberCard')
    numberCards.forEach(card => {
      if (card.title === '总请求数') {
        card.data = {
          value: (overview.totalRequests || 0).toLocaleString(),
          trend: formatTrend(overview.totalTrend)
        }
      } else if (card.title === '平均响应时间') {
        card.data = {
          value: Math.round(overview.avgResponseTime || 0) + 'ms',
          trend: formatTrend(overview.avgTrend)
        }
      } else if (card.title === '错误率') {
        card.data = {
          value: (overview.errorRate || 0).toFixed(2) + '%',
          trend: formatTrend(overview.errorTrend)
        }
      } else if (card.title === 'QPS') {
        card.data = {
          value: (overview.qps || 0).toFixed(1),
          trend: formatTrend(overview.qpsTrend)
        }
      }
    })

    const lineCharts = widgets.value.filter(w => w.component === 'LineChart')
    lineCharts.forEach(lc => {
      lc.data = (trend.timestamps || []).map((t, i) => ({
        time: t,
        value: (trend.infoCounts || [])[i] || 0
      }))
      const chartInst = chartInstances.value[lc.id]
      if (chartInst) {
        chartInst.setOption({
          xAxis: { data: lc.data.map(d => d.time) },
          series: [{ data: lc.data.map(d => d.value) }]
        })
      }
    })
  } catch {
    // API 调用失败时保持默认显示
  }
}

const initWidgetChart = (el, widget) => {
  if (!el || !widget || widget.component !== 'LineChart') return
  if (chartInstances.value[widget.id]) return

  setTimeout(() => {
    const chart = echarts.init(el)
    const option = {
      grid: { left: '10%', right: '10%', top: '10%', bottom: '10%' },
      xAxis: {
        type: 'category',
        data: (widget.data || []).map(d => d.time),
        axisLabel: { fontSize: 10 }
      },
      yAxis: { type: 'value', axisLabel: { fontSize: 10 } },
      series: [{
        type: 'line',
        smooth: true,
        data: (widget.data || []).map(d => d.value),
        areaStyle: { color: 'rgba(64,158,255,0.1)' },
        itemStyle: { color: '#409EFF' }
      }]
    }
    chart.setOption(option)
    chartInstances.value[widget.id] = chart
  }, 100)
}

const handleAddWidget = () => {
  if (!newWidget.value.type || !newWidget.value.title) {
    ElMessage.warning('请填写完整信息')
    return
  }

  const widgetId = Date.now()
  const widget = {
    id: widgetId,
    type: newWidget.value.type,
    title: newWidget.value.title,
    icon: 'DataLine',
    colSpan: newWidget.value.colSpan || 1,
    rowSpan: newWidget.value.rowSpan || 1,
    data: newWidget.value.type === 'number-card'
      ? { value: '-', trend: '-' }
      : [],
    component: newWidget.value.type === 'number-card' ? 'NumberCard' : 'LineChart',
    chartId: newWidget.value.type === 'line-chart' ? `chart-${widgetId}` : undefined
  }

  widgets.value.push(widget)
  showAddWidgetDialog.value = false
  newWidget.value = { type: '', title: '', colSpan: 1, rowSpan: 1 }
  ElMessage.success('控件添加成功')
}

const handleEditWidget = (widget) => {
  ElMessage.info('编辑功能开发中')
}

const handleRemoveWidget = (id) => {
  if (chartInstances.value[id]) {
    chartInstances.value[id].dispose()
    delete chartInstances.value[id]
  }
  widgets.value = widgets.value.filter(w => w.id !== id)
  ElMessage.success('控件已删除')
}

const handleResetLayout = () => {
  Object.values(chartInstances.value).forEach(chart => { chart?.dispose() })
  chartInstances.value = {}

  widgets.value = [
    { id: 1, type: 'number-card', title: '总请求数', icon: 'DataLine', colSpan: 1, rowSpan: 1, data: { value: '-', trend: '-' }, component: 'NumberCard' }
  ]
  loadDashboardData()
  ElMessage.success('布局已重置')
}

onMounted(() => {
  loadDashboardData()
  window.addEventListener('resize', () => {
    Object.values(chartInstances.value).forEach(chart => { chart?.resize() })
  })
})
</script>

<style scoped>
.widget-dashboard {
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

.widgets-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  grid-auto-rows: 150px;
}

.widget-item {
  background: white;
  border-radius: 4px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

.widget-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid #f0f0f0;
}

.widget-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 500;
  color: #333;
}

.widget-actions {
  display: flex;
  gap: 4px;
}

.widget-content {
  flex: 1;
  overflow: hidden;
}

.number-card-widget {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  height: 100%;
}

.number-value {
  font-size: 32px;
  font-weight: bold;
  color: #333;
  margin-bottom: 8px;
}

.number-trend {
  font-size: 14px;
}

.trend-up {
  color: #52c41a;
}

.trend-down {
  color: #ff4d4f;
}

.line-chart-widget {
  width: 100%;
  height: 100%;
  min-height: 120px;
}
</style>

