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
                <span class="trace-link" @click.stop="handleTraceClick(item.trace, item.dateTime)">
                  <el-icon class="trace-icon"><Link /></el-icon>
                  {{ item.trace }}
                </span>
                <el-button
                  type="primary"
                  link
                  size="small"
                  @click.stop="toggleTraceExpand(index, item.trace, item.dateTime)"
                  class="expand-trace-btn"
                >
                  {{ expandedTraces[item.trace] ? '收起链路' : '展开链路' }}
                </el-button>
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
            <el-descriptions-item label="类型">{{ item.type || '-' }}</el-descriptions-item>
            <el-descriptions-item label="服务名称">{{ item.serviceName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="日志级别">
              <el-tag v-if="item.logLevel" :type="item.logLevel === 'ERROR' ? 'danger' : item.logLevel === 'WARN' ? 'warning' : 'info'" size="small">
                {{ item.logLevel }}
              </el-tag>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item label="用户ID">{{ item.userId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="用户账号">{{ item.userAccount || '-' }}</el-descriptions-item>
            <el-descriptions-item label="页面ID">{{ item.pageid || '-' }}</el-descriptions-item>
            <el-descriptions-item label="来源页面">{{ item.fromPage || '-' }}</el-descriptions-item>
            <el-descriptions-item label="应用版本">{{ item.appVersion || '-' }}</el-descriptions-item>
            <el-descriptions-item label="AB测试值">{{ item.abValue || '-' }}</el-descriptions-item>
            <el-descriptions-item label="前端上报时间">{{ item.formattedDateTime || '-' }}</el-descriptions-item>
            <el-descriptions-item label="日志时间">{{ item.formattedLogTime || '-' }}</el-descriptions-item>
            <el-descriptions-item label="会话ID">{{ item.sessionId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="客户端IP">{{ item.clientIp || '-' }}</el-descriptions-item>
            <el-descriptions-item label="响应码">{{ item.responseStatus || '-' }}</el-descriptions-item>
            <el-descriptions-item label="响应参数" :span="2">
              <pre v-if="item.responseData" class="response-data">{{ formatResponseData(item.responseData) }}</pre>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item label="原始日志" :span="2">
              <pre v-if="item.logMessage" class="log-message-text">{{ item.logMessage }}</pre>
              <span v-else>-</span>
            </el-descriptions-item>
          </el-descriptions>
        </div>
        
        <!-- 链路展开区域 -->
        <div v-if="item.trace && expandedTraces[item.trace]" class="trace-expand-area">
          <div class="trace-chain-container">
            <div v-if="traceData[item.trace]?.loading" class="trace-loading">
              <el-icon class="is-loading"><Loading /></el-icon>
              <span>加载链路数据...</span>
            </div>
            <div v-else-if="traceData[item.trace]?.nodes?.length > 0" class="trace-chain-tree-table">
              <div class="tree-header">
                <div class="tree-col-path">接口路径 / 服务</div>
                <div class="tree-col-info">类型 / 位置</div>
                <div class="tree-col-duration">耗时</div>
                <div class="tree-col-bar">耗时分布</div>
              </div>
              <div
                v-for="(node, nodeIndex) in getVisibleTraceNodes(item.trace)"
                :key="node._origIndex"
                class="tree-row"
                :class="{ 'row-error': node.status === 'error', 'row-active': traceData[item.trace]?.selectedNode === node._origIndex, 'row-leaf': !node.hasChildren }"
                :style="{ paddingLeft: (node.level || 0) * 28 + 16 + 'px' }"
                @click="selectTraceNode(item.trace, node._origIndex)"
              >
                <div class="tree-col-path">
                  <span
                    v-if="node.hasChildren"
                    class="expand-btn"
                    :class="{ expanded: node.expanded }"
                    @click.stop="toggleTraceNodeExpand(item.trace, node._origIndex)"
                  >
                    <el-icon><CaretRight /></el-icon>
                  </span>
                  <span v-else class="leaf-dot"></span>
                  <el-tooltip :content="node.apiPath || node.serviceName" placement="top" :show-after="300">
                    <span class="path-text" @click.stop="copyPath(node.apiPath)">{{ node.apiPath || node.serviceName }}</span>
                  </el-tooltip>
                </div>
                <div class="tree-col-info">
                  <el-tag size="small" effect="plain" class="service-tag">{{ node.serviceName }}</el-tag>
                  <span class="info-type">{{ node.callType || 'URL' }}</span>
                  <span class="info-ip">{{ node.ip || '--' }}</span>
                </div>
                <div class="tree-col-duration">
                  <span class="duration-value" :class="{ 'duration-slow': node.duration > 1000 }">{{ node.duration }}ms</span>
                </div>
                <div class="tree-col-bar">
                  <div class="duration-bar-bg">
                    <div class="duration-bar" :class="{ 'bar-error': node.status === 'error' }" :style="{ width: getTraceBarWidth(item.trace, node.duration) + '%' }"></div>
                  </div>
                </div>
              </div>
              <div v-if="traceData[item.trace]?.selectedNode !== null && traceData[item.trace]?.selectedNode !== undefined" class="node-detail">
                <div class="detail-header">
                  <span class="detail-title">{{ traceData[item.trace].nodes[traceData[item.trace].selectedNode].serviceName }} - 日志详情</span>
                  <el-button size="small" @click="traceData[item.trace].selectedNode = null">关闭</el-button>
                </div>
                <div class="detail-logs">
                  <div v-for="(log, lIndex) in traceData[item.trace].nodes[traceData[item.trace].selectedNode].logs" :key="lIndex" class="log-item">
                    <div class="log-header">
                      <el-tag :type="log.level === 'ERROR' ? 'danger' : log.level === 'WARN' ? 'warning' : 'info'" size="small">
                        {{ log.level }}
                      </el-tag>
                      <span class="log-time">{{ log.formattedTime }}</span>
                    </div>
                    <div class="log-message">{{ log.message }}</div>
                  </div>
                </div>
              </div>
            </div>
            <div v-else class="trace-empty">
              <span>该链路暂无数据</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, ArrowUp, Link, Loading, CaretRight } from '@element-plus/icons-vue'
import { generateSlsLink } from '@/utils/sls'
import { getTraceChain } from '@/api/trace-chain.js'

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
const expandedTraces = reactive({})
const traceData = reactive({})

const toggleExpand = (index) => {
  expandedItems.value[index] = !expandedItems.value[index]
}

const toggleTraceExpand = async (index, traceId, timestamp) => {
  if (!traceId) return
  
  if (expandedTraces[traceId]) {
    expandedTraces[traceId] = false
    return
  }

  expandedTraces[traceId] = true

  if (!traceData[traceId]) {
    traceData[traceId] = { loading: true, nodes: [], selectedNode: null }
    try {
      const res = await getTraceChain(traceId, '24h')
      const data = res?.data || res
      const rawNodes = data.nodes || []

      const childCount = {}
      rawNodes.forEach((node, idx) => {
        const pid = node.parentId
        if (pid !== undefined && pid !== null && pid >= 0) {
          childCount[pid] = (childCount[pid] || 0) + 1
        }
      })

      traceData[traceId] = {
        loading: false,
        nodes: rawNodes.map((node, idx) => ({
          ...node,
          id: node.id !== undefined ? node.id : idx,
          parentId: node.parentId !== undefined ? node.parentId : -1,
          level: 0,
          hasChildren: (childCount[idx] || 0) > 0,
          expanded: true,
          callType: node.callType || 'URL',
          ip: node.ip || ''
        })),
        selectedNode: null
      }
    } catch (e) {
      console.error('获取链路数据失败:', e)
      traceData[traceId] = { loading: false, nodes: [], selectedNode: null }
    }
  }
}

const getTraceBarWidth = (traceId, duration) => {
  const nodes = traceData[traceId]?.nodes || []
  const maxDuration = Math.max(...nodes.map(n => n.duration || 0), 1)
  return ((duration || 0) / maxDuration) * 100
}

const getVisibleTraceNodes = (traceId) => {
  const td = traceData[traceId]
  if (!td || !td.nodes || td.nodes.length === 0) return []

  const allNodes = td.nodes
  const childrenMap = {}
  allNodes.forEach((node, idx) => {
    const pid = node.parentId
    if (pid !== undefined && pid !== null && pid >= 0) {
      if (!childrenMap[pid]) childrenMap[pid] = []
      childrenMap[pid].push(idx)
    }
  })

  const result = []
  const addWithChildren = (idx, level) => {
    const node = allNodes[idx]
    result.push({ ...node, level, _origIndex: idx })
    if (node.expanded && childrenMap[idx]) {
      for (const childIdx of childrenMap[idx]) {
        addWithChildren(childIdx, level + 1)
      }
    }
  }

  allNodes.forEach((node, idx) => {
    if (node.parentId === undefined || node.parentId === null || node.parentId < 0) {
      addWithChildren(idx, 0)
    }
  })

  return result
}

const toggleTraceNodeExpand = (traceId, index) => {
  if (traceData[traceId]) {
    traceData[traceId].nodes[index].expanded = !traceData[traceId].nodes[index].expanded
  }
}

const selectTraceNode = (traceId, index) => {
  if (traceData[traceId]) {
    traceData[traceId].selectedNode = traceData[traceId].selectedNode === index ? null : index
  }
}

const copyPath = async (path) => {
  if (!path) return
  try {
    await navigator.clipboard.writeText(path)
    ElMessage.success('已复制')
  } catch {
    const ta = document.createElement('textarea')
    ta.value = path
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    ElMessage.success('已复制')
  }
}

// 处理追踪ID点击，跳转到内部链路详情页
const handleTraceClick = (traceId, timestamp = null) => {
  if (!traceId) return
  const query = { traceId }
  if (timestamp) {
    // Format millisecond timestamp as "yyyy-MM-dd HH:mm:ss.SSS" for backend parsing
    const date = new Date(timestamp)
    const year = date.getFullYear()
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    const hour = String(date.getHours()).padStart(2, '0')
    const minute = String(date.getMinutes()).padStart(2, '0')
    const second = String(date.getSeconds()).padStart(2, '0')
    const millisecond = String(date.getMilliseconds()).padStart(3, '0')
    query.timestamp = `${year}-${month}-${day} ${hour}:${minute}:${second}.${millisecond}`
  }
  router.push({ path: '/gateway/trace', query })
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
  Object.keys(expandedTraces).forEach(key => delete expandedTraces[key])
  Object.keys(traceData).forEach(key => delete traceData[key])
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

.content-details .expand-trace-btn {
  flex-shrink: 0;
  font-size: 12px;
  margin-left: 4px;
}

.trace-expand-area {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed #e4e7ed;
}

.trace-chain-container {
  background: #f9f9f9;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 16px;
}

.trace-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 20px;
  color: #909399;
  font-size: 12px;
}

.trace-empty {
  text-align: center;
  padding: 20px;
  color: #909399;
  font-size: 12px;
}

.trace-chain {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.trace-chain-tree-table {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  overflow: hidden;
  background: white;
}

.trace-chain-tree-table .tree-header {
  display: flex;
  align-items: center;
  padding: 10px 14px;
  background: #f5f7fa;
  border-bottom: 1px solid #ebeef5;
  font-size: 11px;
  font-weight: 600;
  color: #909399;
  letter-spacing: 0.5px;
}

.trace-chain-tree-table .tree-col-path {
  flex: 2;
  min-width: 0;
}

.trace-chain-tree-table .tree-col-info {
  flex: 1.5;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}

.trace-chain-tree-table .tree-col-duration {
  width: 80px;
  text-align: right;
}

.trace-chain-tree-table .tree-col-bar {
  width: 120px;
}

.trace-chain-tree-table .tree-row {
  display: flex;
  align-items: center;
  padding: 10px 14px;
  border-bottom: 1px solid #f2f3f5;
  cursor: pointer;
  transition: all 0.2s;
  font-size: 12px;
}

.trace-chain-tree-table .tree-row:last-child {
  border-bottom: none;
}

.trace-chain-tree-table .tree-row:hover {
  background: #f5f8ff;
}

.trace-chain-tree-table .tree-row.row-active {
  background: #ecf5ff;
}

.trace-chain-tree-table .tree-row.row-error {
  background: #fff8f8;
}

.trace-chain-tree-table .tree-row.row-leaf {
  opacity: 0.85;
}

.trace-chain-tree-table .expand-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  margin-right: 6px;
  border-radius: 3px;
  cursor: pointer;
  color: #909399;
  transition: all 0.2s;
  flex-shrink: 0;
}

.trace-chain-tree-table .expand-btn:hover {
  background: #ecf5ff;
  color: #409eff;
}

.trace-chain-tree-table .expand-btn .el-icon {
  font-size: 12px;
  transition: transform 0.2s;
}

.trace-chain-tree-table .expand-btn.expanded .el-icon {
  transform: rotate(90deg);
}

.trace-chain-tree-table .leaf-dot {
  display: inline-block;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #dcdfe6;
  margin-right: 9px;
  margin-left: 7px;
  flex-shrink: 0;
}

.trace-chain-tree-table .path-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #303133;
  font-weight: 500;
  cursor: pointer;
  padding: 1px 4px;
  border-radius: 3px;
  transition: all 0.2s;
}

.trace-chain-tree-table .path-text:hover {
  background: #ecf5ff;
  color: #409eff;
}

.trace-chain-tree-table .service-tag {
  font-size: 10px !important;
  height: 18px !important;
  line-height: 16px !important;
  padding: 0 5px !important;
  border-radius: 9px !important;
  flex-shrink: 0;
}

.trace-chain-tree-table .info-type {
  color: #909399;
  font-size: 10px;
  background: #f4f4f5;
  padding: 1px 5px;
  border-radius: 3px;
  flex-shrink: 0;
}

.trace-chain-tree-table .info-ip {
  color: #c0c4cc;
  font-size: 10px;
  font-family: 'SF Mono', 'Monaco', monospace;
  flex-shrink: 0;
}

.trace-chain-tree-table .duration-value {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
  font-family: 'SF Mono', 'Monaco', monospace;
}

.trace-chain-tree-table .duration-slow {
  color: #f56c6c;
}

.trace-chain-tree-table .duration-bar-bg {
  width: 100%;
  height: 6px;
  background: #f0f2f5;
  border-radius: 3px;
  overflow: hidden;
}

.trace-chain-tree-table .duration-bar {
  height: 100%;
  background: linear-gradient(90deg, #67c23a, #95d475);
  border-radius: 3px;
  min-width: 3px;
  transition: width 0.4s ease;
}

.trace-chain-tree-table .duration-bar.bar-error {
  background: linear-gradient(90deg, #f56c6c, #f89898);
}

.chain-flow {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0;
  overflow-x: auto;
  padding: 8px 0;
}

.chain-node-wrapper {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.chain-node {
  background: white;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 10px 12px;
  min-width: 140px;
  cursor: pointer;
  transition: all 0.2s;
}

.chain-node:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.15);
}

.chain-node.node-active {
  border-color: #409eff;
  background: #ecf5ff;
}

.chain-node.node-error {
  border-color: #f56c6c;
  background: #fef0f0;
}

.chain-node .node-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.chain-node .node-service {
  font-weight: 500;
  font-size: 12px;
  color: #303133;
}

.chain-node .node-status {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 3px;
}

.chain-node .node-status.normal {
  background: #f0f9eb;
  color: #67c23a;
}

.chain-node .node-status.error {
  background: #fef0f0;
  color: #f56c6c;
}

.chain-node .node-path {
  font-size: 11px;
  color: #909399;
  margin-bottom: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 140px;
  cursor: pointer;
  padding: 1px 4px;
  border-radius: 3px;
  transition: background 0.2s;
}

.chain-node .node-path:hover {
  background: #e6f7ff;
  color: #1890ff;
}

.chain-node .node-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #606266;
}

.chain-arrow {
  display: flex;
  align-items: center;
  padding: 0 8px;
  color: #909399;
  font-size: 11px;
  flex-shrink: 0;
}

.chain-arrow .arrow-line {
  width: 20px;
  height: 1px;
  background: #c0c4cc;
}

.chain-arrow .arrow-duration {
  padding: 0 4px;
  color: #606266;
}

.chain-arrow .arrow-head {
  color: #c0c4cc;
  font-size: 10px;
}

.node-detail {
  background: white;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 12px;
  margin-top: 8px;
}

.node-detail .detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #e4e7ed;
}

.node-detail .detail-title {
  font-weight: 500;
  font-size: 13px;
  color: #303133;
}

.node-detail .detail-logs {
  max-height: 300px;
  overflow-y: auto;
}

.node-detail .log-item {
  padding: 8px;
  border-bottom: 1px solid #f0f0f0;
}

.node-detail .log-item:last-child {
  border-bottom: none;
}

.node-detail .log-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.node-detail .log-time {
  font-size: 11px;
  color: #909399;
}

.node-detail .log-message {
  font-size: 12px;
  color: #606266;
  word-break: break-all;
  line-height: 1.5;
}

.log-message-text {
  font-family: monospace;
  font-size: 12px;
  color: #606266;
  background: #f5f7fa;
  padding: 8px 12px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
  margin: 0;
}
</style>


