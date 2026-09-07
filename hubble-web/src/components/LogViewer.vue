<template>
  <div class="log-viewer">
    <el-table
      :data="sortedLogs"
      stripe
      border
      max-height="500"
      style="width: 100%"
      size="small"
    >
      <el-table-column prop="time" label="时间" width="220">
        <template #default="{ row }">
          {{ formatTime(row.time) }}
        </template>
      </el-table-column>

      <el-table-column prop="level" label="级别" width="100">
        <template #default="{ row }">
          <el-tag
            :type="getLevelType(row.level || '')"
            size="small"
          >
            {{ row.level || 'INFO' }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column prop="trace" label="追踪ID" width="220">
        <template #default="{ row }">
          <div v-if="row.trace" class="trace-cell">
            <span class="trace-id" :title="row.trace">{{ row.trace }}</span>
            <el-button
              link
              type="primary"
              size="small"
              @click="handleViewTrace(row.trace, row.time)"
            >
              链路
            </el-button>
            <el-button
              link
              type="primary"
              size="small"
              @click="handleOpenSls(row.trace)"
              class="sls-link"
            >
              <el-icon><Link /></el-icon>
              SLS
            </el-button>
          </div>
          <span v-else>-</span>
        </template>
      </el-table-column>

      <el-table-column prop="message" label="日志内容" min-width="300" show-overflow-tooltip>
        <template #default="{ row }">
          <pre class="log-message">{{ row.message || row.line || formatFields(row.fields) }}</pre>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            size="small"
            @click="handleViewDetail(row)"
          >
            详情
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 日志详情对话框 -->
    <el-dialog
      v-model="detailVisible"
      title="日志详情"
      width="800px"
    >
      <el-descriptions :column="2" border>
        <el-descriptions-item label="时间">
          {{ formatTime(detailLog?.time) }}
        </el-descriptions-item>
        <el-descriptions-item label="级别">
          <el-tag :type="getLevelType(detailLog?.level || '')" size="small">
            {{ detailLog?.level || 'INFO' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="容器IP">
          {{ detailLog?.containerIp || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="容器名称">
          {{ detailLog?.containerName || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="线程">
          {{ detailLog?.thread || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="行号">
          {{ detailLog?.line || '-' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="detailLog?.trace" label="追踪ID" :span="2">
          <div class="trace-detail">
            <span>{{ detailLog.trace }}</span>
            <el-button
              link
              type="primary"
              size="small"
              @click="handleViewTrace(detailLog.trace, detailLog.time)"
            >
              查看链路
            </el-button>
            <el-button
              link
              type="primary"
              size="small"
              @click="handleOpenSls(detailLog.trace)"
            >
              <el-icon><Link /></el-icon>
              在 SLS 控制台查看
            </el-button>
          </div>
        </el-descriptions-item>
        <el-descriptions-item label="消息" :span="2">
          <pre class="log-detail-message">{{ detailLog?.message || detailLog?.line || '-' }}</pre>
        </el-descriptions-item>
        <el-descriptions-item
          v-if="detailLog?.fields && Object.keys(detailLog.fields).length > 0"
          label="其他字段"
          :span="2"
        >
          <el-table :data="formatFieldsAsTable(detailLog.fields)" border size="small">
            <el-table-column prop="key" label="字段名" width="200" />
            <el-table-column prop="value" label="字段值" />
          </el-table>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { Link } from '@element-plus/icons-vue'
import { openSlsConsole } from '@/utils/sls'

const router = useRouter()

const props = defineProps({
  logs: {
    type: Array,
    default: () => []
  },
  startTime: {
    type: Number,
    default: undefined
  },
  endTime: {
    type: Number,
    default: undefined
  },
  logstore: {
    type: String,
    default: undefined
  }
})

const detailVisible = ref(false)
const detailLog = ref(null)

// 按时间排序的日志列表（从早到晚）
const sortedLogs = computed(() => {
  if (!props.logs || props.logs.length === 0) {
    return []
  }
  
  const logsCopy = [...props.logs]
  
  const sorted = logsCopy.sort((a, b) => {
    const timeA = (a.time || '').trim()
    const timeB = (b.time || '').trim()
    
    if (!timeA && !timeB) return 0
    if (!timeA) return 1
    if (!timeB) return -1
    
    const dateA = new Date(timeA)
    const dateB = new Date(timeB)
    
    const timestampA = dateA.getTime()
    const timestampB = dateB.getTime()
    
    if (!isNaN(timestampA) && !isNaN(timestampB)) {
      const diff = timestampA - timestampB
      if (diff === 0) {
        return timeA.localeCompare(timeB)
      }
      return diff
    }
    
    return timeA.localeCompare(timeB)
  })
  
  return sorted
})

// 格式化时间
const formatTime = (time) => {
  if (!time) return '-'
  
  const timeNum = parseInt(time)
  if (!isNaN(timeNum) && timeNum.toString() === time.trim()) {
    const date = new Date(timeNum * 1000)
    const year = date.getFullYear()
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    const hour = String(date.getHours()).padStart(2, '0')
    const minute = String(date.getMinutes()).padStart(2, '0')
    const second = String(date.getSeconds()).padStart(2, '0')
    const millisecond = String(date.getMilliseconds()).padStart(3, '0')
    return `${year}-${month}-${day} ${hour}:${minute}:${second}.${millisecond}`
  }
  
  try {
    const date = new Date(time)
    if (!isNaN(date.getTime())) {
      const year = date.getFullYear()
      const month = String(date.getMonth() + 1).padStart(2, '0')
      const day = String(date.getDate()).padStart(2, '0')
      const hour = String(date.getHours()).padStart(2, '0')
      const minute = String(date.getMinutes()).padStart(2, '0')
      const second = String(date.getSeconds()).padStart(2, '0')
      const millisecond = String(date.getMilliseconds()).padStart(3, '0')
      return `${year}-${month}-${day} ${hour}:${minute}:${second}.${millisecond}`
    }
  } catch (e) {
    // 解析失败
  }
  
  if (time.match(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/)) {
    return time + '.000'
  }
  
  return time
}

// 获取日志级别类型
const getLevelType = (level) => {
  const levelUpper = (level || '').toUpperCase()
  if (levelUpper.includes('ERROR') || levelUpper.includes('FATAL')) {
    return 'danger'
  } else if (levelUpper.includes('WARN')) {
    return 'warning'
  } else if (levelUpper.includes('INFO')) {
    return 'info'
  } else if (levelUpper.includes('DEBUG')) {
    return ''
  }
  return 'info'
}

// 格式化字段为字符串
const formatFields = (fields) => {
  if (!fields || Object.keys(fields).length === 0) {
    return ''
  }
  
  return Object.entries(fields)
    .map(([key, value]) => `${key}: ${value}`)
    .join(', ')
}

// 格式化字段为表格数据
const formatFieldsAsTable = (fields) => {
  return Object.entries(fields).map(([key, value]) => ({
    key,
    value
  }))
}

// 查看详情
const handleViewDetail = (log) => {
  detailLog.value = log
  detailVisible.value = true
}

// 打开 SLS 控制台
const handleOpenSls = (traceId) => {
  openSlsConsole(traceId, {
    startTime: props.startTime,
    endTime: props.endTime,
    logstore: props.logstore
  })
}

// 跳转到链路详情页
const handleViewTrace = (traceId, timestamp = null) => {
  const query = { traceId }
  if (timestamp) {
    // Format timestamp as "yyyy-MM-dd HH:mm:ss.SSS" for backend parsing
    const timeNum = parseInt(timestamp)
    if (!isNaN(timeNum)) {
      const date = new Date(timeNum * 1000)
      const year = date.getFullYear()
      const month = String(date.getMonth() + 1).padStart(2, '0')
      const day = String(date.getDate()).padStart(2, '0')
      const hour = String(date.getHours()).padStart(2, '0')
      const minute = String(date.getMinutes()).padStart(2, '0')
      const second = String(date.getSeconds()).padStart(2, '0')
      const millisecond = String(date.getMilliseconds()).padStart(3, '0')
      query.timestamp = `${year}-${month}-${day} ${hour}:${minute}:${second}.${millisecond}`
    }
  }
  router.push({ path: '/gateway/trace', query })
}
</script>

<style scoped>
.log-viewer .log-message {
  margin: 0;
  font-family: 'Courier New', monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.trace-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.trace-cell .trace-id {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'Courier New', monospace;
  font-size: 12px;
}

.trace-cell .sls-link {
  flex-shrink: 0;
  padding: 0 4px;
}

.trace-detail {
  display: flex;
  align-items: center;
  gap: 12px;
}

.trace-detail span {
  font-family: 'Courier New', monospace;
  word-break: break-all;
}

.log-detail-message {
  margin: 0;
  padding: 10px;
  background-color: #f5f7fa;
  border-radius: 4px;
  font-family: 'Courier New', monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow-y: auto;
}
</style>


