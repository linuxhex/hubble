<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { getApiDegradation } from '@/api/gateway.js'

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
  { label: '本月 vs 上月', value: 'month' }
]

const fetchData = async () => {
  loading.value = true
  try {
    const res = await getApiDegradation({ compareMode: compareMode.value })
    tableData.value = res.data || []
    lastUpdated.value = new Date().toLocaleTimeString()
    countdown = REFRESH_INTERVAL / 1000
  } catch {
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

const formatRate = (rate) => {
  if (rate >= 100) return `${rate.toFixed(1)}%`
  return `${rate.toFixed(1)}%`
}

const startAutoRefresh = () => {
  refreshTimer = setInterval(() => {
    countdown--
    if (countdown <= 0) {
      fetchData()
    }
    countdownText.value = `${Math.max(0, countdown)}s`
  }, 1000)
}

const handleVisibilityChange = () => {
  if (!document.hidden) {
    fetchData()
  }
}

onMounted(() => {
  fetchData()
  startAutoRefresh()
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
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
      <el-table
        v-loading="loading"
        :data="tableData"
        stripe
        border
        size="small"
        style="width: 100%"
      >
        <el-table-column label="排名" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="getRankType(row.rank)" size="small" effect="dark">
              {{ row.rank }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="apiPath" label="接口" min-width="200" show-overflow-tooltip />
        <el-table-column label="当前平均耗时" width="140" align="right">
          <template #default="{ row }">
            <span class="time-value">{{ row.currentAvgTime.toFixed(1) }} ms</span>
          </template>
        </el-table-column>
        <el-table-column label="上期平均耗时" width="140" align="right">
          <template #default="{ row }">
            <span class="time-value muted">{{ row.previousAvgTime.toFixed(1) }} ms</span>
          </template>
        </el-table-column>
        <el-table-column label="劣化幅度" width="140" align="center" prop="degradationRate">
          <template #default="{ row }">
            <span class="degradation-rate">
              ↑ {{ formatRate(row.degradationRate) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="当前请求数" width="110" align="right" prop="currentCount" />
        <el-table-column label="上期请求数" width="110" align="right" prop="previousCount" />
      </el-table>

      <el-empty v-if="!loading && tableData.length === 0" description="暂无劣化接口，表现良好" />
    </div>
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

.time-value {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.time-value.muted {
  color: #909399;
}

.degradation-rate {
  color: #f56c6c;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
</style>
