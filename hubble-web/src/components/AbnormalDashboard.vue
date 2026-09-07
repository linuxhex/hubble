<template>
  <div class="abnormal-dashboard">
    <div class="dashboard-header">
      <div class="dashboard-title">
        <h2>异常大盘</h2>
      </div>
      <div class="filter-area">
        <el-radio-group v-model="timeRange" size="small" @change="onTimeRangeChange">
          <el-radio-button value="15m">15分钟</el-radio-button>
          <el-radio-button value="30m">30分钟</el-radio-button>
          <el-radio-button value="1h">1小时</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <div v-loading="loading" class="service-list">
      <div v-for="(slot, index) in timeSlots" :key="index" class="time-slot">
        <div class="time-header">
          <span class="time">{{ slot.displayTime }}</span>
          <span class="counter">事件</span>
        </div>
        <div class="service-items">
          <el-tooltip
            v-for="(svc, sIndex) in slot.services"
            :key="sIndex"
            placement="right"
            :show-after="300"
          >
            <template #content>
              <div class="tooltip-content">
                <div><b>{{ svc.name }}</b> - {{ svc.count }} 条错误</div>
                <div>阈值: 红={{ svc.redThreshold }} 粉={{ svc.yellowThreshold }}</div>
                <div style="color:#999;margin-top:4px">点击查看详情</div>
              </div>
            </template>
            <div
              class="service-item clickable"
              :class="{ 'error-bg': svc.isRed, 'warn-bg': svc.isYellow }"
              @click="handleServiceClick(svc.name)"
            >
              <div class="service-name">{{ svc.name }}</div>
              <div class="service-count">{{ svc.count }}</div>
            </div>
          </el-tooltip>
          <div v-if="slot.services.length === 0" class="no-service">无异常</div>
        </div>
      </div>

      <div v-if="!loading && timeSlots.length === 0" class="empty-tip">暂无异常数据</div>
    </div>

    <ServiceDrillDown
      v-model:visible="drillDownVisible"
      :service-name="drillDownService"
    />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { getMinuteTimeline } from '@/api/alert.js'
import ServiceDrillDown from './ServiceDrillDown.vue'

const router = useRouter()
const timeSlots = ref([])
const loading = ref(false)
const timeRange = ref('15m')
let refreshTimer = null

const drillDownVisible = ref(false)
const drillDownService = ref('')

const startAutoRefresh = () => {
  stopAutoRefresh()
  refreshTimer = setInterval(fetchData, 30000)
}

const stopAutoRefresh = () => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

const fetchData = async () => {
  loading.value = true
  try {
    const res = await getMinuteTimeline(timeRange.value)
    if (res.code !== 200) return

    const data = res.data
    const timeline = data.timeline || []

    timeSlots.value = timeline
      .map(point => {
        const services = (point.services || [])
          .slice(0, 8)
          .map(s => ({
            name: s.name,
            count: s.count,
            isRed: s.status === 'RED',
            isYellow: s.status === 'YELLOW',
            redThreshold: s.redThreshold || 50,
            yellowThreshold: s.yellowThreshold || 20
          }))

        return {
          displayTime: point.minute ? point.minute.substring(11, 16) : '',
          minute: point.minute,
          status: point.status,
          totalErrors: point.totalErrors || 0,
          services
        }
      })
      .filter(slot => slot.services.length > 0)
      .sort((a, b) => b.minute.localeCompare(a.minute))
  } catch (e) {
    console.error('获取异常数据失败:', e)
  } finally {
    loading.value = false
  }
}

const handleServiceClick = (serviceName) => {
  drillDownService.value = serviceName
  drillDownVisible.value = true
}

const onTimeRangeChange = () => {
  fetchData()
  startAutoRefresh()
}

const handleVisibility = () => {
  if (document.visibilityState === 'visible') {
    fetchData()
    startAutoRefresh()
  } else {
    stopAutoRefresh()
  }
}

onMounted(() => {
  fetchData()
  startAutoRefresh()
  document.addEventListener('visibilitychange', handleVisibility)
})

onUnmounted(() => {
  stopAutoRefresh()
  document.removeEventListener('visibilitychange', handleVisibility)
})
</script>

<style scoped>
.abnormal-dashboard {
  background: white;
  padding: 10px;
  border-radius: 4px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow: auto;
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.dashboard-title h2 {
  font-size: 16px;
  font-weight: 600;
  color: #333;
  margin: 0;
}

.filter-area {
  display: flex;
  align-items: center;
  gap: 12px;
}

.time-filter {
  display: flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
  font-size: 13px;
  color: #606266;
}

.service-list {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 8px;
  overflow-y: auto;
  padding-bottom: 4px;
  max-height: calc(100vh - 100px);
}

.time-slot {
  min-width: 0;
  border: 1px solid #e8e8e8;
  border-radius: 2px;
  height: fit-content;
}

.time-header {
  padding: 4px 8px;
  background: #fafafa;
  border-bottom: 1px solid #e8e8e8;
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: 24px;
}

.time {
  font-weight: bold;
  color: #333;
  font-size: 12px;
}

.counter {
  color: #1890ff;
  font-size: 11px;
}

.service-items {
  padding: 2px 4px;
  max-height: 320px;
  overflow-y: auto;
}

.service-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 2px 0;
  border-bottom: 1px solid #f0f0f0;
  font-size: 11px;
}

.service-item:last-child {
  border-bottom: none;
}

.service-item.clickable {
  cursor: pointer;
}

.service-item.clickable:hover {
  opacity: 0.85;
}

.service-name {
  color: #1890ff;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-count {
  color: #666;
  font-family: monospace;
}

/* 告警 - 红色背景 */
.service-item.error-bg {
  background-color: #ff4d4f;
  border-radius: 2px;
  padding: 3px 4px;
  margin: 0 -4px;
}

.service-item.error-bg .service-name,
.service-item.error-bg .service-count {
  color: white;
}

/* 预警 - 粉色背景 */
.service-item.warn-bg {
  background-color: #ffadd2;
  border-radius: 2px;
  padding: 3px 4px;
  margin: 0 -4px;
}

.service-item.warn-bg .service-name {
  color: #eb2f96;
}

.service-item.warn-bg .service-count {
  color: #eb2f96;
}

.no-service {
  font-size: 11px;
  color: #999;
  text-align: center;
  padding: 8px 0;
}

.empty-tip {
  text-align: center;
  color: #999;
  font-size: 13px;
  padding: 40px 0;
  grid-column: 1 / -1;
}
</style>
