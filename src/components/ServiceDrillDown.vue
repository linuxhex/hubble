<template>
  <el-dialog
    :model-value="visible"
    :title="`服务下钻 - ${serviceName}`"
    width="900px"
    destroy-on-close
    @close="$emit('update:visible', false)"
  >
    <div v-loading="loading" class="drill-down">
      <!-- 异常分类统计 -->
      <div class="section">
        <h4>异常分类</h4>
        <div class="breakdown-cards">
          <div class="breakdown-card error-card">
            <div class="card-value">{{ breakdown.total || 0 }}</div>
            <div class="card-label">ERROR 总数</div>
          </div>
          <div class="breakdown-card npe-card">
            <div class="card-value">{{ breakdown.npe || 0 }}</div>
            <div class="card-label">空指针 NPE</div>
          </div>
          <div class="breakdown-card timeout-card">
            <div class="card-value">{{ breakdown.timeout || 0 }}</div>
            <div class="card-label">超时 Timeout</div>
          </div>
          <div class="breakdown-card warn-card">
            <div class="card-value">{{ breakdown.warn || 0 }}</div>
            <div class="card-label">WARN</div>
          </div>
          <div class="breakdown-card other-card">
            <div class="card-value">{{ breakdown.other || 0 }}</div>
            <div class="card-label">其他错误</div>
          </div>
        </div>
      </div>

      <!-- 下游依赖 -->
      <div v-if="downstreamServices.length > 0" class="section">
        <h4>下游依赖</h4>
        <div class="downstream-tags">
          <el-tag v-for="ds in downstreamServices" :key="ds" type="info" size="small" class="ds-tag">
            {{ ds }}
          </el-tag>
        </div>
      </div>

      <!-- 错误日志列表 -->
      <div class="section">
        <h4>错误日志（最近 {{ errorLogs.length }} 条）</h4>
        <el-table :data="errorLogs" size="small" border style="width: 100%" max-height="400">
          <el-table-column prop="time" label="时间" width="110">
            <template #default="{ row }">
              {{ formatTime(row.time) }}
            </template>
          </el-table-column>
          <el-table-column prop="level" label="级别" width="70">
            <template #default="{ row }">
              <el-tag :type="row.level === 'ERROR' ? 'danger' : 'warning'" size="small">
                {{ row.level }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="logger" label="Logger" width="180" show-overflow-tooltip />
          <el-table-column prop="message" label="错误信息" min-width="300" show-overflow-tooltip />
          <el-table-column prop="traceId" label="TraceId" width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.traceId" class="trace-id">{{ row.traceId }}</span>
              <span v-else class="no-trace">-</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { getServiceDrillDown } from '@/api/alert.js'

const props = defineProps({
  visible: Boolean,
  serviceName: String,
  timeRange: { type: String, default: '15m' }
})

defineEmits(['update:visible'])

const loading = ref(false)
const errorLogs = ref([])
const breakdown = ref({})
const downstreamServices = ref([])

const fetchData = async () => {
  if (!props.serviceName) return
  loading.value = true
  try {
    const res = await getServiceDrillDown(props.serviceName, props.timeRange)
    if (res.code === 200) {
      const data = res.data
      errorLogs.value = data.errorLogs || []
      breakdown.value = data.errorBreakdown || {}
      downstreamServices.value = data.downstreamServices || []
    }
  } catch (e) {
    console.error('服务下钻查询失败:', e)
  } finally {
    loading.value = false
  }
}

const formatTime = (ts) => {
  if (!ts) return '-'
  const t = typeof ts === 'string' && ts.length < 12 ? parseInt(ts) * 1000 : parseInt(ts)
  if (isNaN(t)) return ts
  const d = new Date(t)
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

watch(() => props.visible, (val) => {
  if (val) fetchData()
})
</script>

<style scoped>
.drill-down {
  max-height: 70vh;
  overflow-y: auto;
}

.section {
  margin-bottom: 16px;
}

.section h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  color: #333;
  border-left: 3px solid #1890ff;
  padding-left: 8px;
}

.breakdown-cards {
  display: flex;
  gap: 12px;
}

.breakdown-card {
  flex: 1;
  padding: 10px;
  border-radius: 4px;
  text-align: center;
  color: white;
}

.error-card { background: #ff4d4f; }
.npe-card { background: #cf1322; }
.timeout-card { background: #fa8c16; }
.warn-card { background: #faad14; }
.other-card { background: #8c8c8c; }

.card-value {
  font-size: 22px;
  font-weight: bold;
  font-family: monospace;
}

.card-label {
  font-size: 11px;
  margin-top: 2px;
  opacity: 0.9;
}

.downstream-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.ds-tag {
  font-size: 12px;
}

.trace-id {
  font-family: monospace;
  font-size: 11px;
  color: #1890ff;
}

.no-trace {
  color: #ccc;
}
</style>
