<template>
  <div class="gateway-trace">
    <div class="search-area">
      <div class="search-row">
        <span class="search-label">链路ID</span>
        <el-input
          v-model="traceId"
          placeholder="请输入链路ID (如: fa7257a48b... 或 1138123626...)"
          clearable
          style="width: 400px"
          @keyup.enter="fetchTraceChain"
        />
        <el-select v-model="timeRange" style="width: 120px; margin-left: 12px">
          <el-option label="近15分钟" value="15m" />
          <el-option label="近30分钟" value="30m" />
          <el-option label="近1小时" value="1h" />
          <el-option label="近6小时" value="6h" />
          <el-option label="近24小时" value="24h" />
        </el-select>
        <el-button type="primary" @click="fetchTraceChain" :loading="loading" style="margin-left: 12px">查询</el-button>
      </div>
    </div>

    <div v-if="!traceId && nodes.length === 0" class="empty-state">
      <p v-if="recentTraces.length === 0">请输入链路ID查询链路详情</p>
      <p style="font-size: 12px; color: #999; margin-top: 8px;" v-if="recentTraces.length === 0">链路ID可在日志搜索、异常大盘等处获取</p>
      <div v-if="recentTraces.length > 0" class="recent-traces">
        <p style="font-size: 14px; font-weight: 500; margin-bottom: 12px;">最近链路（点击查询）</p>
        <div class="recent-traces-list">
          <div
            v-for="trace in recentTraces"
            :key="trace.traceId"
            class="recent-trace-item"
            @click="selectRecentTrace(trace)"
          >
            <span class="trace-id">{{ trace.traceId }}</span>
            <span class="trace-app">{{ trace.appName }}</span>
            <span class="trace-url">{{ trace.url || '--' }}</span>
            <span class="trace-time">{{ trace.timestamp }}</span>
          </div>
        </div>
      </div>
    </div>

    <div v-else-if="searched && nodes.length === 0" class="empty-state">
      <p>未找到该链路ID对应的数据</p>
    </div>

    <div v-else-if="nodes.length > 0" class="trace-chain">
      <div class="chain-header">
        <span class="chain-title">链路详情</span>
        <span class="chain-meta">TraceID: {{ currentTraceId }} | 共 {{ totalLogs }} 条日志 | {{ nodes.length }} 个服务</span>
      </div>

      <div class="trace-summary">
        <div class="summary-item">
          <span class="summary-label">总耗时</span>
          <span class="summary-value">{{ totalDuration }}ms</span>
        </div>
        <div class="summary-item">
          <span class="summary-label">服务数</span>
          <span class="summary-value">{{ nodes.length }}</span>
        </div>
        <div class="summary-item">
          <span class="summary-label">日志数</span>
          <span class="summary-value">{{ totalLogs }}</span>
        </div>
        <div class="summary-item" v-if="errorCount > 0">
          <span class="summary-label">异常</span>
          <span class="summary-value summary-error">{{ errorCount }}</span>
        </div>
      </div>

      <div class="chain-tree-table">
        <div class="tree-header">
          <div class="tree-col-path">接口路径 / 服务</div>
          <div class="tree-col-info">类型 / 位置</div>
          <div class="tree-col-duration">耗时</div>
          <div class="tree-col-bar">耗时分布</div>
        </div>
        <div
          v-for="(node, idx) in visibleNodes"
          :key="node._origIndex"
          class="tree-row"
          :class="{ 'row-error': node.status === 'error', 'row-active': selectedNode === node._origIndex, 'row-leaf': !node.hasChildren }"
          :style="{ paddingLeft: (node.level || 0) * 28 + 16 + 'px' }"
          @click="selectNode(node._origIndex)"
        >
          <div class="tree-col-path">
            <span
              v-if="node.hasChildren"
              class="expand-btn"
              :class="{ expanded: node.expanded }"
              @click.stop="toggleExpand(node._origIndex)"
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
              <div class="duration-bar" :class="{ 'bar-error': node.status === 'error' }" :style="{ width: getBarWidth(node.duration) + '%' }"></div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="selectedNode !== null" class="node-detail">
        <div class="detail-header">
          <div class="detail-title-section">
            <span class="detail-title">{{ nodes[selectedNode].serviceName }} - 日志详情</span>
            <span class="detail-meta">{{ nodes[selectedNode].logCount }} 条日志 | 耗时 {{ nodes[selectedNode].duration }}ms</span>
          </div>
          <el-button size="small" @click="selectedNode = null">关闭</el-button>
        </div>
        <div class="detail-logs">
          <div v-for="(log, lIndex) in nodes[selectedNode].logs" :key="lIndex" class="log-item" :class="{'log-error': log.level === 'ERROR'}">
            <div class="log-header">
              <el-tag :type="log.level === 'ERROR' ? 'danger' : log.level === 'WARN' ? 'warning' : 'info'" size="small">
                {{ log.level }}
              </el-tag>
              <span class="log-time">{{ log.formattedTime }}</span>
              <span v-if="log.thread" class="log-thread">{{ log.thread }}</span>
            </div>
            <div class="log-message">{{ log.message }}</div>
            <div v-if="log.stackTrace" class="log-stack">
              <details>
                <summary>查看堆栈</summary>
                <pre>{{ log.stackTrace }}</pre>
              </details>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CaretRight } from '@element-plus/icons-vue'
import { getTraceChain } from '@/api/trace-chain.js'
import { queryGatewayLogs } from '@/api/keyword-log-query.js'

const route = useRoute()
const traceId = ref('')
const currentTraceId = ref('')
const timeRange = ref('1h')
const traceTimestamp = ref(null)
const loading = ref(false)
const searched = ref(false)
const nodes = ref([])
const totalLogs = ref(0)
const selectedNode = ref(null)
const recentTraces = ref([])

const totalDuration = computed(() => {
  return nodes.value.reduce((sum, node) => sum + (node.duration || 0), 0)
})

const errorCount = computed(() => {
  return nodes.value.filter(node => node.status === 'error').length
})

const visibleNodes = computed(() => {
  const allNodes = nodes.value
  if (allNodes.length === 0) return []

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

  // Start from root nodes (parentId === -1 or no parent)
  allNodes.forEach((node, idx) => {
    if (node.parentId === undefined || node.parentId === null || node.parentId < 0) {
      addWithChildren(idx, 0)
    }
  })

  return result
})

const fetchTraceChain = async () => {
  if (!traceId.value.trim()) return
  loading.value = true
  searched.value = false
  selectedNode.value = null
  try {
    const res = await getTraceChain(traceId.value.trim(), timeRange.value, traceTimestamp.value)
    const data = res?.data || res
    currentTraceId.value = data.traceId || traceId.value
    const rawNodes = data.nodes || []
    const maxDuration = Math.max(...rawNodes.map(n => n.duration || 0), 1)

    // 先构建 hasChildren 映射
    const childCount = {}
    rawNodes.forEach((node, idx) => {
      const pid = node.parentId
      if (pid !== undefined && pid !== null && pid >= 0) {
        childCount[pid] = (childCount[pid] || 0) + 1
      }
    })

    nodes.value = rawNodes.map((node, idx) => ({
      ...node,
      id: node.id !== undefined ? node.id : idx,
      parentId: node.parentId !== undefined ? node.parentId : -1,
      level: 0,
      hasChildren: (childCount[idx] || 0) > 0,
      expanded: true,
      callType: node.callType || 'URL',
      ip: node.ip || '',
      barWidth: ((node.duration || 0) / maxDuration) * 100,
      _origIndex: idx
    }))
    totalLogs.value = data.totalLogs || 0
    searched.value = true
  } catch (e) {
    console.error('获取链路数据失败:', e)
    nodes.value = []
    searched.value = true
  } finally {
    loading.value = false
  }
}

const getBarWidth = (duration) => {
  const maxDuration = Math.max(...nodes.value.map(n => n.duration || 0), 1)
  return ((duration || 0) / maxDuration) * 100
}

const toggleExpand = (index) => {
  nodes.value[index].expanded = !nodes.value[index].expanded
}

const selectNode = (index) => {
  selectedNode.value = selectedNode.value === index ? null : index
}

const copyPath = async (path, event) => {
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

const fetchRecentTraces = async () => {
  try {
    const end = new Date()
    const start = new Date(end.getTime() - 60 * 60 * 1000)
    const fmt = (d) => {
      const p = (n) => String(n).padStart(2, '0')
      return `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
    }
    const res = await queryGatewayLogs({ page: 1, pageSize: 50, startTime: fmt(start), endTime: fmt(end) })
    const data = res?.data || res
    const records = data?.records || data?.list || []
    const seen = new Set()
    const traces = []
    for (const r of records) {
      if (r.traceId && !seen.has(r.traceId)) {
        seen.add(r.traceId)
        traces.push({ traceId: r.traceId, appName: r.appName, url: r.url, timestamp: r.timestamp })
        if (traces.length >= 10) break
      }
    }
    recentTraces.value = traces
  } catch (e) {
    console.error('获取最近链路失败:', e)
  }
}

const selectRecentTrace = (trace) => {
  traceId.value = trace.traceId
  fetchTraceChain()
}

onMounted(() => {
  const queryTraceId = route.query.traceId
  const queryTimestamp = route.query.timestamp
  if (queryTraceId) {
    traceId.value = queryTraceId
    if (queryTimestamp) {
      traceTimestamp.value = queryTimestamp
    }
    fetchTraceChain()
  } else {
    fetchRecentTraces()
  }
})
</script>

<style scoped>
.gateway-trace {
  padding: 16px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  background: #f5f7fa;
}

.search-area {
  background: white;
  padding: 20px 24px;
  border-radius: 8px;
  border: 1px solid #ebeef5;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.search-row {
  display: flex;
  align-items: center;
}

.search-label {
  font-size: 14px;
  font-weight: 500;
  color: #333;
  margin-right: 12px;
  white-space: nowrap;
}

.empty-state {
  text-align: center;
  padding: 80px 20px;
  color: #666;
  font-size: 14px;
}

.trace-chain {
  flex: 1;
  overflow: auto;
}

.chain-header {
  background: white;
  padding: 14px 20px;
  border-radius: 8px;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border: 1px solid #ebeef5;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

@media (max-width: 768px) {
  .chain-header {
    padding: 8px 12px;
    margin-bottom: 10px;
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }
}

.chain-title {
  font-size: 15px;
  font-weight: 600;
  color: #333;
}

@media (max-width: 768px) {
  .chain-title {
    font-size: 14px;
  }
}

@media (max-width: 480px) {
  .chain-title {
    font-size: 13px;
  }
}

.chain-meta {
  font-size: 12px;
  color: #999;
}

@media (max-width: 480px) {
  .chain-meta {
    font-size: 11px;
  }
}

.trace-summary {
  background: white;
  padding: 16px 20px;
  border-radius: 8px;
  margin-bottom: 12px;
  display: flex;
  gap: 32px;
  flex-wrap: wrap;
  border: 1px solid #ebeef5;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

@media (max-width: 768px) {
  .trace-summary {
    padding: 10px 12px;
    margin-bottom: 10px;
    gap: 16px;
  }
}

@media (max-width: 480px) {
  .trace-summary {
    gap: 12px;
  }
}

.summary-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.summary-label {
  font-size: 12px;
  color: #999;
}

@media (max-width: 480px) {
  .summary-label {
    font-size: 11px;
  }
}

.summary-value {
  font-size: 22px;
  font-weight: 700;
  color: #303133;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
}

@media (max-width: 768px) {
  .summary-value {
    font-size: 16px;
  }
}

@media (max-width: 480px) {
  .summary-value {
    font-size: 15px;
  }
}

.summary-value.summary-error {
  color: #f56c6c;
}

.chain-tree-table {
  background: white;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #ebeef5;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.tree-header {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  background: #f5f7fa;
  border-bottom: 1px solid #ebeef5;
  font-size: 12px;
  font-weight: 600;
  color: #909399;
  letter-spacing: 0.5px;
  text-transform: uppercase;
}

.tree-col-path {
  flex: 2;
  min-width: 0;
}

.tree-col-info {
  flex: 1.5;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.tree-col-duration {
  width: 90px;
  text-align: right;
}

.tree-col-bar {
  width: 140px;
  position: relative;
}

.tree-row {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #f2f3f5;
  cursor: pointer;
  transition: all 0.2s ease;
  font-size: 13px;
  position: relative;
}

.tree-row:last-child {
  border-bottom: none;
}

.tree-row:hover {
  background: #f5f8ff;
}

.tree-row.row-active {
  background: #ecf5ff;
  border-left: 3px solid #409eff;
}

.tree-row.row-error {
  background: #fff8f8;
}

.tree-row.row-error:hover {
  background: #fff0f0;
}

.tree-row.row-leaf {
  opacity: 0.85;
}

.expand-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  margin-right: 8px;
  border-radius: 4px;
  cursor: pointer;
  color: #909399;
  transition: all 0.2s;
  flex-shrink: 0;
}

.expand-btn:hover {
  background: #ecf5ff;
  color: #409eff;
}

.expand-btn .el-icon {
  font-size: 14px;
  transition: transform 0.2s;
}

.expand-btn.expanded .el-icon {
  transform: rotate(90deg);
}

.leaf-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #dcdfe6;
  margin-right: 11px;
  margin-left: 7px;
  flex-shrink: 0;
}

.path-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #303133;
  font-weight: 500;
  font-size: 13px;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 4px;
  transition: all 0.2s;
}

.path-text:hover {
  background: #ecf5ff;
  color: #409eff;
}

.service-tag {
  font-size: 11px !important;
  height: 20px !important;
  line-height: 18px !important;
  padding: 0 6px !important;
  border-radius: 10px !important;
  flex-shrink: 0;
}

.info-type {
  color: #909399;
  font-size: 11px;
  background: #f4f4f5;
  padding: 1px 6px;
  border-radius: 3px;
  flex-shrink: 0;
}

.info-ip {
  color: #c0c4cc;
  font-size: 11px;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
  flex-shrink: 0;
}

.duration-value {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
}

.duration-slow {
  color: #f56c6c;
}

.duration-bar-bg {
  width: 100%;
  height: 8px;
  background: #f0f2f5;
  border-radius: 4px;
  overflow: hidden;
}

.duration-bar {
  height: 100%;
  background: linear-gradient(90deg, #67c23a, #95d475);
  border-radius: 4px;
  min-width: 4px;
  transition: width 0.4s ease;
}

.duration-bar.bar-error {
  background: linear-gradient(90deg, #f56c6c, #f89898);
}

.node-detail {
  background: white;
  border-radius: 8px;
  margin-top: 16px;
  overflow: hidden;
  border: 1px solid #ebeef5;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.detail-header {
  padding: 12px 24px;
  background: #fafafa;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-title-section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.detail-title {
  font-size: 14px;
  font-weight: 600;
  color: #333;
}

.detail-meta {
  font-size: 12px;
  color: #999;
}

.detail-logs {
  padding: 16px 24px;
  max-height: 400px;
  overflow-y: auto;
}

.log-item {
  padding: 12px;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
  margin-bottom: 8px;
  transition: all 0.2s;
}

.log-item:hover {
  border-color: #d9d9d9;
  box-shadow: 0 2px 4px rgba(0,0,0,0.05);
}

.log-item.log-error {
  border-color: #ffccc7;
  background: #fff2f0;
}

.log-item:last-child {
  margin-bottom: 0;
}

.log-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.log-time {
  font-size: 12px;
  color: #999;
  font-family: monospace;
}

.log-thread {
  font-size: 11px;
  color: #666;
  font-family: monospace;
  padding: 2px 6px;
  background: #f5f5f5;
  border-radius: 3px;
}

.log-message {
  font-size: 13px;
  color: #333;
  line-height: 1.6;
  word-break: break-all;
  font-family: monospace;
  white-space: pre-wrap;
  max-height: 200px;
  overflow-y: auto;
}

.log-stack {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #e8e8e8;
}

.log-stack details {
  font-size: 12px;
}

.log-stack summary {
  cursor: pointer;
  color: #1890ff;
  font-weight: 500;
  padding: 4px 0;
}

.log-stack summary:hover {
  color: #40a9ff;
}

.log-stack pre {
  margin-top: 8px;
  padding: 12px;
  background: #f6f8fa;
  border-radius: 4px;
  font-size: 11px;
  line-height: 1.5;
  overflow-x: auto;
  color: #586069;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
}

.recent-traces {
  margin-top: 16px;
  text-align: left;
  max-width: 800px;
  margin-left: auto;
  margin-right: auto;
}

.recent-traces-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.recent-trace-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  background: white;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
}

.recent-trace-item:hover {
  border-color: #409EFF;
  background: #ecf5ff;
}

.trace-id {
  font-family: monospace;
  font-size: 12px;
  color: #409EFF;
  flex-shrink: 0;
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-app {
  font-size: 12px;
  color: #606266;
  flex-shrink: 0;
  min-width: 100px;
}

.trace-url {
  font-size: 12px;
  color: #909399;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-time {
  font-size: 11px;
  color: #c0c4cc;
  flex-shrink: 0;
}
</style>
