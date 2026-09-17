<template>
  <div class="user-behavior">
    <div class="search-bar">
      <div class="search-group">
        <el-input
          v-model="keyword"
          placeholder="手机号或用户ID"
          clearable
          style="width: 200px"
          @keyup.enter="handleQuery"
        />
        <el-date-picker
          v-model="selectedDate"
          type="date"
          placeholder="选择日期"
          value-format="YYYY-MM-DD"
          style="width: 140px"
          :default-value="new Date()"
        />
        <el-button type="primary" @click="handleQuery" :loading="loading">查询</el-button>
      </div>
    </div>

    <div v-if="!hasQueried" class="empty">
      <div class="empty-icon">👤</div>
      <div class="empty-text">输入手机号或用户ID，查看用户访问的接口轨迹</div>
    </div>

    <div v-else-if="items.length === 0" class="empty">
      <div class="empty-icon"></div>
      <div class="empty-text">未找到该用户的接口访问记录</div>
    </div>

    <div v-else class="journey-container">
      <div class="journey-header">
        <div class="header-left">
          <span class="header-title">接口访问轨迹</span>
          <span class="header-badge">{{ items.length }} 次调用</span>
        </div>
        <div class="header-right">
          <span class="header-meta">{{ keyword }}</span>
          <span class="header-divider">·</span>
          <span class="header-meta">{{ selectedDate }}</span>
        </div>
      </div>

      <div class="journey-list">
        <div
          v-for="(item, idx) in items"
          :key="idx"
          class="journey-item"
          :class="{ expanded: item.trace && expandedRows[idx] }"
        >
          <div class="journey-time">
            <span class="time-text">{{ formatTime(item.formattedDateTime) }}</span>
            <span v-if="showDateSeparator(idx)" class="time-date">{{ formatDate(item.formattedDateTime) }}</span>
          </div>

          <div class="journey-connector">
            <div class="connector-dot" :class="getDotClass(item.logLevel)"></div>
            <div v-if="idx < items.length - 1" class="connector-line"></div>
          </div>

          <div class="journey-content">
            <div class="api-card" @click="item.trace && toggleTraceExpand(item, idx)">
              <div class="api-main">
                <span class="api-method" v-if="item.type">{{ item.type }}</span>
                <span class="api-path-text" :class="{ 'path-fallback': !item.api && !item.url }">{{ getApiPath(item) }}</span>
                <span v-if="item.responseStatus" class="api-status" :class="getHttpStatusClass(item.responseStatus)">{{ item.responseStatus }}</span>
              </div>
              <div class="api-meta">
                <span class="meta-service" :style="{ color: getServiceColor(item.serviceName) }">
                  {{ item.serviceName || '-' }}
                </span>
                <span v-if="item.logLevel" class="api-level" :class="'level-' + item.logLevel.toLowerCase()">
                  {{ item.logLevel }}
                </span>
                <span v-if="item.clientIp" class="meta-ip">{{ item.clientIp }}</span>
                <span v-if="item.trace" class="trace-action" @click.stop="toggleTraceExpand(item, idx)">
                  {{ expandedRows[idx] ? '收起' : '链路' }}
                </span>
              </div>
            </div>

            <div v-if="item.trace && expandedRows[idx]" class="trace-panel" @click.stop>
              <div v-if="traceData[item.trace]?.loading" class="trace-loading">
                <el-icon class="is-loading"></el-icon>
                <span>加载中...</span>
              </div>
              <div v-else-if="traceData[item.trace]?.nodes?.length > 0" class="trace-chain-view">
                <div class="chain-summary">
                  <div class="summary-item">
                    <span class="summary-label">总耗时</span>
                    <span class="summary-value">{{ getTraceTotalDuration(item.trace) }}ms</span>
                  </div>
                  <div class="summary-item">
                    <span class="summary-label">服务数</span>
                    <span class="summary-value">{{ getTraceServiceCount(item.trace) }}</span>
                  </div>
                  <div class="summary-item">
                    <span class="summary-label">日志数</span>
                    <span class="summary-value">{{ traceData[item.trace]?.totalLogs || 0 }}</span>
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
                    v-for="(node, ni) in getVisibleTraceNodes(item.trace)"
                    :key="ni"
                    class="tree-row"
                    :class="{ 'row-error': node.status === 'error', 'row-active': traceData[item.trace]?.selectedNode === node._origIndex }"
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
                        <span class="path-text" @click.stop="copyTracePath(node.apiPath || node.serviceName)">{{ node.apiPath || node.serviceName }}</span>
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
                </div>
                <div v-if="traceData[item.trace]?.selectedNode !== null && traceData[item.trace]?.selectedNode !== undefined" class="node-detail">
                  <div class="detail-header">
                    <div class="detail-title-section">
                      <span class="detail-title">{{ traceData[item.trace].nodes[traceData[item.trace].selectedNode].serviceName }} - 日志详情</span>
                      <span class="detail-meta">{{ (traceData[item.trace].nodes[traceData[item.trace].selectedNode].logs || []).length }} 条日志 | 耗时 {{ traceData[item.trace].nodes[traceData[item.trace].selectedNode].duration }}ms</span>
                    </div>
                    <el-button size="small" @click="traceData[item.trace].selectedNode = null">关闭</el-button>
                  </div>
                  <div class="detail-logs">
                    <div v-for="(log, li) in traceData[item.trace].nodes[traceData[item.trace].selectedNode].logs" :key="li" class="log-item" :class="{'log-error': log.level === 'ERROR'}">
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
              <div v-else class="trace-empty">暂无链路数据</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading, CaretRight } from '@element-plus/icons-vue'
import { queryUserBehaviorTrace } from '@/api/user-behavior-trace-query.js'
import { getTraceChain } from '@/api/trace-chain.js'

const keyword = ref('')
const selectedDate = ref('')
const loading = ref(false)
const hasQueried = ref(false)
const items = ref([])
const expandedRows = reactive({})
const traceData = reactive({})

const serviceColors = {}
const colorPalette = ['#1890ff', '#52c41a', '#faad14', '#f5222d', '#722ed1', '#13c2c2', '#eb2f96', '#fa8c16']
let colorIdx = 0

const getServiceColor = (name) => {
  if (!name) return '#999'
  if (!serviceColors[name]) {
    serviceColors[name] = colorPalette[colorIdx % colorPalette.length]
    colorIdx++
  }
  return serviceColors[name]
}

const formatTime = (dt) => {
  if (!dt) return ''
  return dt.substring(11, 19)
}

const formatDate = (dt) => {
  if (!dt) return ''
  return dt.substring(0, 10)
}

const showDateSeparator = (idx) => {
  if (idx === 0) return false
  const prev = items.value[idx - 1]?.formattedDateTime
  const curr = items.value[idx]?.formattedDateTime
  if (!prev || !curr) return false
  return prev.substring(0, 10) !== curr.substring(0, 10)
}

const getApiPath = (item) => {
  if (item.api) return item.api
  if (item.url) return item.url
  if (item.pageName) return item.pageName
  return item.serviceName || '-'
}

const getHttpStatusClass = (status) => {
  if (!status) return ''
  const code = parseInt(status)
  if (code >= 500) return 'status-error'
  if (code >= 400) return 'status-warn'
  return 'status-ok'
}

const getDotClass = (level) => {
  if (level === 'ERROR') return 'dot-error'
  if (level === 'WARN') return 'dot-warn'
  return 'dot-normal'
}

const getTodayDate = () => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const handleQuery = async () => {
  if (!keyword.value.trim()) return
  if (!selectedDate.value) selectedDate.value = getTodayDate()

  loading.value = true
  hasQueried.value = true
  items.value = []
  Object.keys(expandedRows).forEach(k => delete expandedRows[k])
  Object.keys(traceData).forEach(k => delete traceData[k])

  try {
    const res = await queryUserBehaviorTrace({
      keyword: keyword.value.trim(),
      date: selectedDate.value,
      limit: 100
    })
    const data = res?.data || res
    items.value = data?.items || []
  } catch (e) {
    console.error('查询失败:', e)
    items.value = []
  } finally {
    loading.value = false
  }
}

const toggleTraceExpand = async (row, idx) => {
  const traceId = row.trace
  if (!traceId) return

  if (expandedRows[idx]) {
    expandedRows[idx] = false
    return
  }

  expandedRows[idx] = true

  if (!traceData[traceId]) {
    traceData[traceId] = { loading: true, nodes: [], selectedNode: null, totalLogs: 0 }
    try {
      const res = await getTraceChain(traceId, '24h')
      const data = res?.data || res
      const rawNodes = data.nodes || []
      const maxDuration = Math.max(...rawNodes.map(n => n.duration || 0), 1)

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
          id: node.id ?? idx,
          parentId: node.parentId ?? -1,
          level: 0,
          hasChildren: (childCount[idx] || 0) > 0,
          expanded: true,
          callType: node.callType || 'HTTP',
          ip: node.ip || '',
          barWidth: ((node.duration || 0) / maxDuration) * 100
        })),
        selectedNode: null,
        totalLogs: data.totalLogs || 0
      }
    } catch (e) {
      console.error('获取链路失败:', e)
      traceData[traceId] = { loading: false, nodes: [], selectedNode: null, totalLogs: 0 }
    }
  }
}

const getVisibleTraceNodes = (traceId) => {
  const td = traceData[traceId]
  if (!td?.nodes?.length) return []

  const nodes = td.nodes
  const childrenMap = {}
  nodes.forEach((n, i) => {
    const p = n.parentId
    if (p !== undefined && p !== null && p >= 0) {
      if (!childrenMap[p]) childrenMap[p] = []
      childrenMap[p].push(i)
    }
  })

  const result = []
  const walk = (i, level) => {
    const n = nodes[i]
    result.push({ ...n, level, _origIndex: i })
    if (n.expanded && childrenMap[i]) {
      childrenMap[i].forEach(c => walk(c, level + 1))
    }
  }

  nodes.forEach((n, i) => {
    if (n.parentId === undefined || n.parentId === null || n.parentId < 0) {
      walk(i, 0)
    }
  })

  return result
}

const toggleTraceNodeExpand = (traceId, idx) => {
  if (traceData[traceId]) {
    traceData[traceId].nodes[idx].expanded = !traceData[traceId].nodes[idx].expanded
  }
}

const selectTraceNode = (traceId, idx) => {
  if (traceData[traceId]) {
    traceData[traceId].selectedNode = traceData[traceId].selectedNode === idx ? null : idx
  }
}

const getTraceTotalDuration = (traceId) => {
  const td = traceData[traceId]
  if (!td?.nodes?.length) return 0
  return td.nodes.reduce((sum, n) => sum + (n.duration || 0), 0)
}

const getTraceServiceCount = (traceId) => {
  const td = traceData[traceId]
  if (!td?.nodes?.length) return 0
  return new Set(td.nodes.map(n => n.serviceName).filter(Boolean)).size
}

const getTraceBarWidth = (traceId, duration) => {
  const td = traceData[traceId]
  if (!td?.nodes?.length) return 0
  const maxDuration = Math.max(...td.nodes.map(n => n.duration || 0), 1)
  return ((duration || 0) / maxDuration) * 100
}

const copyTracePath = async (path) => {
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

onMounted(() => {
  selectedDate.value = getTodayDate()
  keyword.value = '13800138000'
  handleQuery()
})
</script>

<style scoped>
.user-behavior {
  padding: 24px;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #f0f2f5;
}

.search-bar {
  background: white;
  padding: 20px 24px;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  margin-bottom: 20px;
}

.search-group {
  display: flex;
  gap: 12px;
  align-items: center;
}

.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #999;
}

.empty-icon {
  font-size: 64px;
  margin-bottom: 16px;
  opacity: 0.4;
}

.empty-text {
  font-size: 15px;
  color: #666;
}

.journey-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: white;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

.journey-header {
  padding: 20px 24px;
  border-bottom: 1px solid #f0f0f0;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #262626;
}

.header-badge {
  background: #1890ff;
  color: white;
  padding: 2px 10px;
  border-radius: 12px;
  font-size: 13px;
  font-weight: 500;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #8c8c8c;
}

.header-meta {
  font-weight: 500;
}

.header-divider {
  color: #d9d9d9;
}

.journey-list {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

.journey-item {
  display: flex;
  gap: 16px;
}

.journey-time {
  flex-shrink: 0;
  width: 72px;
  padding-top: 10px;
  text-align: right;
}

.time-text {
  font-size: 13px;
  font-weight: 500;
  color: #262626;
  font-family: 'SF Mono', Monaco, monospace;
}

.time-date {
  display: block;
  font-size: 11px;
  color: #bfbfbf;
  margin-top: 2px;
  font-family: 'SF Mono', Monaco, monospace;
}

.journey-connector {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 16px;
  flex-shrink: 0;
}

.connector-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #d9d9d9;
  border: 2px solid white;
  box-shadow: 0 0 0 2px #d9d9d9;
  flex-shrink: 0;
  margin-top: 12px;
  z-index: 1;
}

.connector-dot.dot-error {
  background: #f5222d;
  box-shadow: 0 0 0 2px #f5222d;
}

.connector-dot.dot-warn {
  background: #faad14;
  box-shadow: 0 0 0 2px #faad14;
}

.connector-dot.dot-normal {
  background: #1890ff;
  box-shadow: 0 0 0 2px #1890ff;
}

.connector-line {
  width: 2px;
  flex: 1;
  background: #e8e8e8;
  margin-top: 4px;
}

.journey-content {
  flex: 1;
  min-width: 0;
  padding-bottom: 16px;
}

.api-card {
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  padding: 12px 14px;
  cursor: pointer;
  transition: all 0.2s;
}

.api-card:hover {
  background: #f5f5f5;
  border-color: #d9d9d9;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.expanded .api-card {
  background: #e6f7ff;
  border-color: #91d5ff;
  border-radius: 6px 6px 0 0;
}

.api-main {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.api-method {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: #1890ff;
  background: #e6f7ff;
  padding: 1px 6px;
  border-radius: 3px;
  text-transform: uppercase;
  font-family: 'SF Mono', Monaco, monospace;
}

.api-path-text {
  flex: 1;
  font-size: 13px;
  color: #262626;
  font-family: 'SF Mono', Monaco, monospace;
  word-break: break-all;
  line-height: 1.5;
  font-weight: 500;
}

.api-path-text.path-fallback {
  color: #8c8c8c;
  font-weight: 400;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

.api-status {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 3px;
  font-family: 'SF Mono', Monaco, monospace;
}

.api-status.status-ok {
  background: #f6ffed;
  color: #52c41a;
}

.api-status.status-warn {
  background: #fff7e6;
  color: #faad14;
}

.api-status.status-error {
  background: #fff1f0;
  color: #f5222d;
}

.api-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.meta-service {
  font-weight: 600;
  font-size: 12px;
}

.meta-ip {
  color: #bfbfbf;
  font-family: 'SF Mono', Monaco, monospace;
  font-size: 11px;
}

.api-level {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 500;
}

.level-info {
  background: #f5f5f5;
  color: #8c8c8c;
}

.level-warn {
  background: #fff7e6;
  color: #d48806;
}

.level-error {
  background: #fff1f0;
  color: #cf1322;
}

.trace-action {
  color: #1890ff;
  white-space: nowrap;
  font-weight: 500;
  font-size: 12px;
  margin-left: auto;
  cursor: pointer;
}

.trace-action:hover {
  color: #40a9ff;
}

.expanded .trace-action {
  color: #8c8c8c;
}

.trace-panel {
  background: white;
  border: 1px solid #91d5ff;
  border-top: none;
  border-radius: 0 0 6px 6px;
  padding: 12px 14px;
}

.trace-loading,
.trace-empty {
  text-align: center;
  padding: 20px;
  color: #8c8c8c;
  font-size: 13px;
}

.trace-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.trace-chain-view {
  font-size: 13px;
}

.chain-summary {
  display: flex;
  gap: 24px;
  padding: 12px 0;
  margin-bottom: 12px;
  border-bottom: 1px solid #f0f0f0;
}

.summary-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.summary-label {
  font-size: 12px;
  color: #8c8c8c;
}

.summary-value {
  font-size: 18px;
  font-weight: 600;
  color: #262626;
  font-family: 'SF Mono', Monaco, monospace;
}

.chain-tree-table {
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  overflow: hidden;
}

.tree-header {
  display: flex;
  align-items: center;
  padding: 10px 16px;
  background: #fafafa;
  border-bottom: 1px solid #f0f0f0;
  font-size: 12px;
  font-weight: 600;
  color: #8c8c8c;
}

.tree-col-path {
  flex: 1;
  min-width: 0;
}

.tree-col-info {
  width: 180px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.tree-col-duration {
  width: 80px;
  flex-shrink: 0;
  text-align: right;
}

.tree-col-bar {
  width: 120px;
  flex-shrink: 0;
}

.tree-row {
  display: flex;
  align-items: center;
  padding: 10px 16px;
  border-bottom: 1px solid #f5f5f5;
  cursor: pointer;
  transition: background 0.15s;
}

.tree-row:last-child {
  border-bottom: none;
}

.tree-row:hover {
  background: #fafafa;
}

.row-active {
  background: #e6f7ff;
}

.row-error {
  background: #fff8f8;
}

.expand-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  color: #8c8c8c;
  cursor: pointer;
  flex-shrink: 0;
  margin-right: 6px;
}

.expand-btn .el-icon {
  font-size: 11px;
  transition: transform 0.2s;
}

.expand-btn.expanded .el-icon {
  transform: rotate(90deg);
}

.leaf-dot {
  display: inline-block;
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  margin-right: 6px;
}

.leaf-dot::after {
  content: '';
  display: block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #d9d9d9;
  margin: 5px auto 0;
}

.path-text {
  color: #262626;
  font-family: 'SF Mono', Monaco, monospace;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.path-text:hover {
  color: #1890ff;
}

.service-tag {
  font-size: 11px;
}

.info-type {
  font-size: 11px;
  color: #8c8c8c;
}

.info-ip {
  font-size: 11px;
  color: #bfbfbf;
  font-family: 'SF Mono', Monaco, monospace;
}

.duration-value {
  font-size: 12px;
  color: #595959;
  font-family: 'SF Mono', Monaco, monospace;
}

.duration-value.duration-slow {
  color: #f5222d;
  font-weight: 600;
}

.duration-bar-bg {
  height: 8px;
  background: #f5f5f5;
  border-radius: 4px;
  overflow: hidden;
}

.duration-bar {
  height: 100%;
  background: #52c41a;
  border-radius: 4px;
  transition: width 0.3s;
}

.duration-bar.bar-error {
  background: #f5222d;
}

.node-detail {
  margin-top: 16px;
  border-top: 1px solid #e8e8e8;
  padding-top: 16px;
}

.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.detail-title-section {
  display: flex;
  align-items: center;
  gap: 12px;
}

.detail-title {
  font-size: 13px;
  font-weight: 600;
  color: #262626;
}

.detail-meta {
  font-size: 12px;
  color: #8c8c8c;
}

.detail-logs {
  max-height: 240px;
  overflow-y: auto;
}

.log-item {
  padding: 10px 12px;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  margin-bottom: 8px;
  background: #fafafa;
}

.log-item:last-child {
  margin-bottom: 0;
}

.log-item.log-error {
  border-color: #ffa39e;
  background: #fff2f0;
}

.log-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.log-time {
  font-size: 11px;
  color: #8c8c8c;
  font-family: 'SF Mono', Monaco, monospace;
}

.log-thread {
  font-size: 11px;
  color: #bfbfbf;
}

.log-message {
  font-size: 12px;
  color: #262626;
  font-family: 'SF Mono', Monaco, monospace;
  word-break: break-all;
  white-space: pre-wrap;
  line-height: 1.5;
}

.log-stack {
  margin-top: 6px;
}

.log-stack details {
  font-size: 11px;
  color: #8c8c8c;
}

.log-stack details summary {
  cursor: pointer;
}

.log-stack pre {
  margin-top: 6px;
  padding: 8px;
  background: #f5f5f5;
  border-radius: 4px;
  font-size: 11px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
