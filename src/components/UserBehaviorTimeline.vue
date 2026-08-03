<template>
  <div class="user-behavior-timeline">
    <div
      v-for="(item, index) in items"
      :key="index"
      class="timeline-item"
    >
      <!-- 时间线连接线 -->
      <div class="timeline-line" v-if="index < items.length - 1"></div>
      
      <!-- 时间点 -->
      <div class="timeline-dot"></div>
      
      <!-- 内容卡片 -->
      <div class="timeline-content">
        <div class="content-header" @click="toggleExpand(index)">
          <div class="time-info">
            <span class="time">{{ item.formattedLogTime || item.formattedDateTime }}</span>
          </div>
          <div class="page-info">
            <el-tag size="small" type="info">{{ item.pageName }}</el-tag>
            <el-tag size="small" type="primary">{{ item.terminal }}</el-tag>
            <el-tag size="small" type="success" v-if="item.url" >{{ item.url }}</el-tag>
          </div>
          <div class="expand-icon">
            <el-icon>
              <ArrowDown v-if="!expandedItems[index]" />
              <ArrowUp v-else />
            </el-icon>
          </div>
        </div>
        
        <!-- 详细信息（可展开） -->
        <div v-if="expandedItems[index]" class="content-details">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="追踪ID">
              <span v-if="item.trace" class="trace-link-wrapper">
                <span class="trace-link" @click.stop="handleTraceClick(item.trace)">
                  <el-icon class="trace-icon"><Link /></el-icon>
                  {{ item.trace }}
                </span>
                <el-button
                  link
                  type="primary"
                  size="small"
                  @click.stop="handleOpenSls(item.trace)"
                  class="sls-link"
                >
                  SLS
                </el-button>
              </span>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item label="类型">{{ item.type }}</el-descriptions-item>
            <el-descriptions-item label="用户ID">{{ item.userId }}</el-descriptions-item>
            <el-descriptions-item label="用户账号">{{ item.userAccount }}</el-descriptions-item>
            <el-descriptions-item label="页面ID">{{ item.pageid }}</el-descriptions-item>
            <el-descriptions-item label="来源页面">{{ item.fromPage || '-' }}</el-descriptions-item>
            <el-descriptions-item label="应用版本">{{ item.appVersion }}</el-descriptions-item>
            <el-descriptions-item label="AB测试值">{{ item.abValue || '-' }}</el-descriptions-item>
            <el-descriptions-item label="前端上报时间">{{ item.formattedDateTime || '-' }}</el-descriptions-item>
            <el-descriptions-item label="会话ID">{{ item.sessionId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="客户端IP">{{ item.clientIp || '-' }}</el-descriptions-item>
            <el-descriptions-item label="响应码">{{ item.responseStatus || '-' }}</el-descriptions-item>
            <el-descriptions-item label="响应参数" :span="2">
              <pre v-if="item.responseData" class="response-data">{{ formatResponseData(item.responseData) }}</pre>
              <span v-else>-</span>
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown, ArrowUp, Link } from '@element-plus/icons-vue'
import { generateSlsLink } from '@/utils/sls'

const router = useRouter()

const props = defineProps({
  items: {
    type: Array,
    default: () => []
  },
  timeRange: {
    type: Object,
    default: null
  }
})

const expandedItems = ref({})

const toggleExpand = (index) => {
  expandedItems.value[index] = !expandedItems.value[index]
}

// 处理追踪ID点击，跳转到内部链路详情页
const handleTraceClick = (traceId) => {
  if (!traceId) return
  router.push({ path: '/gateway/trace', query: { traceId } })
}

// 在 SLS 控制台查看
const handleOpenSls = (traceId) => {
  if (!traceId) return
  
  // 构建查询字符串：在message中搜索包含该trace ID的日志
  const escapedTraceId = traceId.replace(/"/g, '\\"')
  const queryString = `__tag__:_container_name_: event-tracing AND message: "*[${escapedTraceId}]*"`
  
  const link = generateSlsLink('', {
    logstore: 'all',
    queryString: queryString,
    startTime: props.timeRange?.startTime,
    endTime: props.timeRange?.endTime
  })
  
  if (link) {
    window.open(link, '_blank')
  }
}

// 格式化响应数据（JSON字符串美化）
const formatResponseData = (responseData) => {
  if (!responseData) return ''
  try {
    const data = JSON.parse(responseData)
    return JSON.stringify(data, null, 2)
  } catch {
    return responseData
  }
}

// 当items变化时，重置展开状态
watch(() => props.items, () => {
  expandedItems.value = {}
}, { deep: true })
</script>

<style scoped>
.user-behavior-timeline {
  position: relative;
  padding: 10px 0;
}

.timeline-item {
  position: relative;
  padding-left: 30px;
  margin-bottom: 12px;
}

.timeline-item:last-child {
  margin-bottom: 0;
}

.timeline-line {
  position: absolute;
  left: 11px;
  top: 20px;
  width: 2px;
  height: calc(100% + 12px);
  background: #e4e7ed;
}

.timeline-dot {
  position: absolute;
  left: 4px;
  top: 4px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #409eff;
  border: 2px solid #fff;
  box-shadow: 0 0 0 2px #409eff;
  z-index: 1;
}

.timeline-content {
  background: #fafafa;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 12px;
  transition: all 0.3s;
}

.timeline-content:hover {
  background: #f5f5f5;
  border-color: #c0c4cc;
}

.content-header {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  user-select: none;
}

.content-header .time-info {
  flex-shrink: 0;
}

.content-header .time-info .time {
  font-weight: 500;
  color: #606266;
  font-size: 12px;
}

.content-header .page-info {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.content-header .expand-icon {
  flex-shrink: 0;
  color: #909399;
  transition: transform 0.3s;
  font-size: 14px;
}

.content-details {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #e4e7ed;
}

.content-details .trace-link-wrapper {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.content-details .trace-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #409eff;
  font-size: 12px;
  font-family: 'Courier New', monospace;
  cursor: pointer;
  text-decoration: none;
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.content-details .trace-link .trace-icon {
  font-size: 12px;
  flex-shrink: 0;
}

.content-details .trace-link:hover {
  text-decoration: underline;
}

.content-details .sls-link {
  flex-shrink: 0;
  font-size: 12px;
}

.content-details .response-data {
  margin: 0;
  padding: 8px;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  font-size: 12px;
  font-family: 'Courier New', monospace;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
}

:deep(.el-descriptions) {
  font-size: 12px;
}

:deep(.el-descriptions__label) {
  font-size: 12px;
  color: #606266;
}

:deep(.el-descriptions__content) {
  font-size: 12px;
  color: #333;
}

:deep(.el-tag) {
  font-size: 11px;
  padding: 2px 6px;
  height: auto;
  line-height: 1.4;
}
</style>


