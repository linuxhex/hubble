<template>
  <el-card header="分类环比" v-loading="loading" class="error-change-rate-chart-card">
    <div v-if="!hasSeries && !loading" class="no-data">
      <el-empty description="暂无数据" />
    </div>
    <div v-else ref="chartRef" class="trend-chart" style="height: 280px"></div>
  </el-card>
</template>

<script setup>
import { ref, onMounted, watch, onBeforeUnmount, computed } from 'vue'
import * as echarts from 'echarts'
import { getTopErrorTypesTrend } from '@/api/errorAnalysis.js'
import { ElMessage } from 'element-plus'

const props = defineProps({
  configId: { type: Number, required: true },
  timeRange: { type: String, default: '30m' }
})

const chartRef = ref(null)
const loading = ref(false)
const trendData = ref(null)

let chart = null

const hasSeries = computed(() => {
  return trendData.value && Object.keys(trendData.value.series || {}).length > 0
})

const fetchData = async () => {
  loading.value = true
  try {
    const timeRange = props.timeRange || '30m'
    const result = await getTopErrorTypesTrend({
      alertConfigId: props.configId,
      timeRange,
      interval: '5m'
    })
    trendData.value = result.data
    if (hasSeries.value) {
      renderChart()
    }
  } catch (error) {
    console.error('获取错误类型数据失败:', error)
    ElMessage.error('获取错误类型数据失败')
  } finally {
    loading.value = false
  }
}

// 计算环比变化率
const calculateChangeRates = (counts) => {
  const rates = [null]
  for (let i = 1; i < counts.length; i++) {
    const prev = counts[i - 1]
    const curr = counts[i]
    if (prev === 0) {
      rates.push(curr > 0 ? 100 : 0)
    } else {
      rates.push(((curr - prev) / prev) * 100)
    }
  }
  return rates
}

const renderChart = () => {
  if (!chartRef.value || !trendData.value || !hasSeries.value) return

  if (!chart) {
    chart = echarts.init(chartRef.value)
  }

  const colors = [
    '#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de',
    '#3ba272', '#fc8452', '#9a60b4', '#ea7ccc', '#d4a5a5'
  ]

  const series = Object.values(trendData.value.series).map((seriesData, index) => ({
    name: seriesData.typeName,
    type: 'line',
    data: calculateChangeRates(seriesData.counts),
    smooth: true,
    symbol: 'circle',
    symbolSize: 6,
    lineStyle: {
      width: 2,
      color: colors[index % colors.length]
    },
    itemStyle: {
      color: colors[index % colors.length]
    }
  }))

  const option = {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line' },
      formatter: (params) => {
        let result = params[0].axisValueLabel + '<br/>'
        params.forEach((item) => {
          const value = item.value
          if (value !== null) {
            result += `${item.marker}${item.seriesName}: ${value >= 0 ? '+' : ''}${value.toFixed(2)}%<br/>`
          }
        })
        return result
      }
    },
    legend: {
      data: Object.values(trendData.value.series).map(s => s.typeName),
      type: 'scroll',
      bottom: 0,
      textStyle: { fontSize: 11 }
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '15%',
      top: '10%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: trendData.value.timestamps.map(t => {
        const date = new Date(t)
        return date.toLocaleString('zh-CN', {
          month: '2-digit',
          day: '2-digit',
          hour: '2-digit',
          minute: '2-digit'
        })
      }),
      axisLabel: {
        rotate: 45,
        interval: 'auto',
        fontSize: 10
      }
    },
    yAxis: {
      type: 'value',
      name: '变化率(%)',
      axisLabel: {
        formatter: '{value}%'
      }
    },
    series
  }

  chart.setOption(option)

  chartRef.value?.addEventListener('wheel', (e) => {
    e.preventDefault()
  }, { passive: false })
}

const handleResize = () => {
  chart?.resize()
}

watch(() => [props.configId, props.timeRange], () => {
  fetchData()
})

onMounted(() => {
  fetchData()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
})
</script>

<style scoped>
.trend-chart {
  width: 100%;
}

.error-change-rate-chart-card {
  height: 100%;
}

.no-data {
  height: 280px;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
