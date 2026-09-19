<template>
  <div ref="chartRef" class="trend-chart"></div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  records: { type: Array, default: () => [] },
  metric: { type: String, required: true } // cpu | memory | qps
})

const chartRef = ref(null)
let chart = null
let resizeObserver = null

const METRICS = {
  cpu: {
    key: 'maxCpu', name: 'CPU 峰值使用率（%）', unit: '%', max: 100,
    yellow: 80, red: 90
  },
  memory: {
    key: 'maxMemory', name: '内存峰值使用率（%）', unit: '%', max: 100,
    yellow: 85, red: 95
  },
  qps: {
    key: 'maxQps', name: '最高 QPS', unit: '', max: null,
    yellow: null, red: null
  }
}

const render = () => {
  if (!chartRef.value) return
  if (!chart) chart = echarts.init(chartRef.value)

  const cfg = METRICS[props.metric] || METRICS.cpu
  const records = [...props.records].sort((a, b) => a.statDate < b.statDate ? -1 : 1)

  const dates = records.map(r => r.statDate)
  const data = records.map(r => {
    const v = r[cfg.key]
    const item = { value: v === null || v === undefined ? null : Number(v) }
    if (r.partialDay === 1) {
      item.symbol = 'diamond'
      item.symbolSize = 9
      item.itemStyle = { color: '#e6a23c' }
    }
    return item
  })

  const markLineData = []
  if (cfg.yellow !== null) {
    markLineData.push({ yAxis: cfg.yellow, lineStyle: { color: '#e6a23c', type: 'dashed' }, label: { formatter: `${cfg.yellow}%`, position: 'insideEndTop' } })
    markLineData.push({ yAxis: cfg.red, lineStyle: { color: '#f56c6c', type: 'dashed' }, label: { formatter: `${cfg.red}%`, position: 'insideEndTop' } })
  }

  const yAxis = { type: 'value' }
  if (cfg.max !== null) {
    yAxis.max = 100
    yAxis.axisLabel = { formatter: '{value}%' }
  }

  chart.setOption({
    title: { text: cfg.name, left: 'center', textStyle: { fontSize: 13, fontWeight: 500, color: '#606266' } },
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const p = params[0]
        if (p.value === null || p.value === undefined) return `${p.axisValue}<br/>无数据`
        const r = records[p.dataIndex]
        let html = `${p.axisValue}<br/>${cfg.name.split('（')[0]}: <b>${p.value}</b>${cfg.unit}`
        if (r && r.partialDay === 1) html += '<br/><span style="color:#e6a23c">⚠ 半天数据（12点采集）</span>'
        if (r && r.avgRt != null) html += `<br/>平均RT: ${r.avgRt}ms`
        return html
      }
    },
    grid: { left: 55, right: 25, top: 40, bottom: 45 },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: 45, fontSize: 10 } },
    yAxis,
    series: [{
      name: cfg.name,
      type: 'line',
      smooth: true,
      connectNulls: false,
      data,
      lineStyle: { width: 2 },
      symbolSize: 6,
      markLine: markLineData.length > 0 ? {
        silent: true,
        symbol: 'none',
        data: markLineData
      } : undefined
    }]
  }, true)
}

const handleResize = () => chart?.resize()

watch(() => [props.records, props.metric], render, { deep: true })

onMounted(() => {
  render()
  // 容器尺寸变化（如 tab 切换后从隐藏变可见）时重算画布
  resizeObserver = new ResizeObserver(handleResize)
  resizeObserver.observe(chartRef.value)
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  resizeObserver?.disconnect()
  resizeObserver = null
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.trend-chart {
  width: 100%;
  height: 300px;
}
</style>
