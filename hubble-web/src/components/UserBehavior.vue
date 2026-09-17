<template>
  <div class="user-behavior">
    <div class="search-area">
      <div class="search-row">
        <span class="search-label">手机号/用户ID</span>
        <el-input
          v-model="keyword"
          placeholder="请输入手机号或用户ID"
          clearable
          style="width: 300px"
          @keyup.enter="handleQuery"
        />
        <el-date-picker
          v-model="selectedDate"
          type="date"
          placeholder="选择日期"
          value-format="YYYY-MM-DD"
          style="width: 160px; margin-left: 12px"
          :default-value="new Date()"
        />
        <el-button type="primary" @click="handleQuery" :loading="loading" style="margin-left: 12px">查询</el-button>
      </div>
    </div>

    <div v-if="!hasQueried" class="empty-state">
      <p>请输入手机号或用户ID查询用户行为</p>
    </div>

    <div v-else-if="items.length === 0" class="empty-state">
      <p>未找到该用户的行为记录</p>
      <div class="empty-tips">
        <p>可能的原因：</p>
        <ul>
          <li>该手机号/用户ID在所选日期无操作记录</li>
          <li>关键字不匹配，请尝试其他关键字</li>
          <li>日志数据尚未同步，请稍后再试</li>
        </ul>
      </div>
    </div>

    <div v-else class="result-area">
      <div class="result-header">
        <span>共 {{ items.length }} 条记录</span>
      </div>
      <el-table ref="behaviorTable" :data="items" style="width: 100%" border @expand-change="handleExpandChange">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div v-if="row.trace && expandedTraces[row.trace]" class="trace-expand-area">
              <div class="trace-chain-container">
                <div v-if="traceData[row.trace]?.loading" class="trace-loading">
                  <el-icon class="is-loading"><Loading /></el-icon>
                  <span>加载链路数据...</span>
                </div>
                <div v-else-if="traceData[row.trace]?.nodes?.length > 0" class="trace-chain-tree-table">
                  <div class="tree-header">
                    <div class="tree-col-path">接口路径 / 服务</div>
                    <div class="tree-col-info">类型 / 位置</div>
                    <div class="tree-col-duration">耗时</div>
                    <div class="tree-col-bar">耗时分布</div>
                  </div>
                  <div
                    v-for="(node, nodeIndex) in getVisibleTraceNodes(row.trace)"
                    :key="node._origIndex"
                    class="tree-row"
                    :class="{ 'row-error': node.status === 'error', 'row-active': traceData[row.trace]?.selectedNode === node._origIndex }"
                    :style="{ paddingLeft: (node.level || 0) * 24 + 12 + 'px' }"
                    @click="selectTraceNode(row.trace, node._origIndex)"
                  >
                    <div class="tree-col-path">
                      <span class="expand-icon" v-if="node.hasChildren" @click.stop="toggleTraceNodeExpand(row.trace, nodeIndex)">
                        {{ node.expanded ? '−' : '+' }}
                      </span>
                      <span class="expand-icon" v-else style="visibility: hidden">+</span>
                      <el-tooltip :content="node.apiPath || node.serviceName" placement="top" :show-after="300">
                        <span class="path-text" @click.stop="copyPath(node.apiPath)">{{ node.apiPath || node.serviceName }}</span>
                      </el-tooltip>
                    </div>
                    <div class="tree-col-info">
                      <span class="info-service">{{ node.serviceName }}</span>
                      <span class="info-type">{{ node.callType || 'URL' }}</span>
                      <span class="info-ip">{{ node.ip || '--' }}</span>
                    </div>
                    <div class="tree-col-duration">
                      <span :class="{ 'duration-slow': node.duration > 1000 }">{{ node.duration }}ms</span>
                    </div>
                    <div class="tree-col-bar">
                      <div class="duration-bar" :style="{ width: getTraceBarWidth(row.trace, node.duration) + '%' }"></div>
                    </div>
                  </div>
                  <div v-if="traceData[row.trace]?.selectedNode !== null && traceData[row.trace]?.selectedNode !== undefined" class="node-detail">
                    <div class="detail-header">
                      <span class="detail-title">{{ traceData[row.trace].nodes[traceData[row.trace].selectedNode].serviceName }} - 日志详情</span>
                      <el-button size="small" @click="traceData[row.trace].selectedNode = null">关闭</el-button>
                    </div>
                    <div class="detail-logs">
                      <div v-for="(log, lIndex) in traceData[row.trace].nodes[traceData[row.trace].selectedNode].logs" :key="lIndex" class="log-item">
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
            <div v-else-if="row.trace" class="trace-expand-area trace-expand-hint">
              <el-button type="primary" link @click="toggleTraceExpand(row)">点击展开链路</el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="formattedDateTime" label="时间" width="180" />
        <el-table-column prop="serviceName" label="服务" width="130" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.serviceName || '--' }}
          </template>
        </el-table-column>
        <el-table-column prop="trace" label="链路ID" width="260">
          <template #default="{ row }">
            <span v-if="row.trace" class="trace-id-text">{{ row.trace }}</span>
            <span v-else>--</span>
          </template>
        </el-table-column>
        <el-table-column prop="logLevel" label="级别" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.logLevel" :type="row.logLevel === 'ERROR' ? 'danger' : row.logLevel === 'WARN' ? 'warning' : 'info'" size="small">
              {{ row.logLevel }}
            </el-tag>
            <span v-else>--</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { queryUserBehaviorTrace } from '@/api/user-behavior-trace-query.js'
import { getTraceChain } from '@/api/trace-chain.js'

const keyword = ref('')
const selectedDate = ref('')
const loading = ref(false)
const hasQueried = ref(false)
const items = ref([])
const expandedTraces = reactive({})
const traceData = reactive({})
const behaviorTable = ref(null)

const getTodayDate = () => {
  const today = new Date()
  const year = today.getFullYear()
  const month = String(today.getMonth() + 1).padStart(2, '0')
  const day = String(today.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

const handleQuery = async () => {
  if (!keyword.value.trim()) return
  if (!selectedDate.value) selectedDate.value = getTodayDate()

  loading.value = true
  hasQueried.value = true
  items.value = []
  Object.keys(expandedTraces).forEach(key => delete expandedTraces[key])
  Object.keys(traceData).forEach(key => delete traceData[key])

  try {
    const res = await queryUserBehaviorTrace({
      keyword: keyword.value.trim(),
      date: selectedDate.value,
      limit: 100
    })
    const data = res?.data || res
    items.value = data?.items || []
  } catch (e) {
    console.error('查询用户行为失败:', e)
    items.value = []
  } finally {
    loading.value = false
  }
}

const toggleTraceExpand = async (row) => {
  const traceId = row.trace
  if (!traceId) return

  // 切换表格行的展开状态
  if (behaviorTable.value) {
    behaviorTable.value.toggleRowExpansion(row)
  }

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

const handleExpandChange = (row, expandedRows) => {
  const traceId = row.trace
  if (!traceId) return
  const isExpanded = expandedRows.some(r => r.trace === traceId && r.formattedDateTime === row.formattedDateTime)
  if (isExpanded && !expandedTraces[traceId]) {
    toggleTraceExpand(row)
  } else if (!isExpanded && expandedTraces[traceId]) {
    expandedTraces[traceId] = false
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

onMounted(() => {
  selectedDate.value = getTodayDate()
  keyword.value = '13800138000'
  handleQuery()
})
</script>

<style scoped>
.user-behavior {
  padding: 16px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  background: #f5f7fa;
}

.search-area {
  background: white;
  padding: 16px 24px;
  border-radius: 4px;
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

.empty-tips {
  margin-top: 16px;
  display: inline-block;
  text-align: left;
  font-size: 13px;
  color: #909399;
  line-height: 1.8;
}

.empty-tips p {
  font-weight: 500;
  color: #606266;
  margin-bottom: 8px;
}

.empty-tips ul {
  margin: 0;
  padding-left: 20px;
}

.result-area {
  flex: 1;
  background: white;
  border-radius: 4px;
  padding: 16px;
  overflow: auto;
}

.result-header {
  margin-bottom: 12px;
  font-size: 12px;
  color: #666;
}

.url-text {
  font-family: monospace;
  font-size: 12px;
}

.trace-id-text {
  font-family: monospace;
  font-size: 12px;
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
  max-width: 240px;
}

.trace-expand-area {
  margin-top: 16px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 16px;
  background: #fafafa;
}

.trace-chain-container {
  width: 100%;
}

.trace-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  color: #999;
  gap: 8px;
}

.trace-empty {
  text-align: center;
  padding: 40px;
  color: #999;
}

.trace-chain {
  width: 100%;
}

.trace-chain-tree-table {
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  overflow: hidden;
}

.trace-chain-tree-table .tree-header {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  background: #fafafa;
  border-bottom: 1px solid #e4e7ed;
  font-size: 12px;
  font-weight: 600;
  color: #606266;
}

.trace-chain-tree-table .tree-col-path {
  flex: 2;
  min-width: 0;
}

.trace-chain-tree-table .tree-col-info {
  flex: 1.5;
  min-width: 0;
}

.trace-chain-tree-table .tree-col-duration {
  width: 70px;
  text-align: right;
}

.trace-chain-tree-table .tree-col-bar {
  width: 100px;
  position: relative;
}

.trace-chain-tree-table .tree-row {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
  transition: background 0.2s;
  font-size: 12px;
}

.trace-chain-tree-table .tree-row:hover {
  background: #f5f7fa;
}

.trace-chain-tree-table .tree-row.row-active {
  background: #ecf5ff;
}

.trace-chain-tree-table .tree-row.row-error {
  background: #fef0f0;
}

.trace-chain-tree-table .expand-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  margin-right: 4px;
  font-size: 11px;
  color: #909399;
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;
}

.trace-chain-tree-table .expand-icon:hover {
  color: #409eff;
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
  transition: background 0.2s;
}

.trace-chain-tree-table .path-text:hover {
  background: #e6f7ff;
  color: #1890ff;
}

.trace-chain-tree-table .info-service {
  color: #409eff;
  margin-right: 6px;
  font-size: 11px;
}

.trace-chain-tree-table .info-type {
  color: #909399;
  margin-right: 6px;
  font-size: 11px;
}

.trace-chain-tree-table .info-ip {
  color: #c0c4cc;
  font-size: 11px;
  font-family: monospace;
}

.trace-chain-tree-table .tree-col-duration {
  font-size: 12px;
  color: #606266;
}

.trace-chain-tree-table .duration-slow {
  color: #f56c6c;
  font-weight: 600;
}

.trace-chain-tree-table .duration-bar {
  height: 14px;
  background: #67c23a;
  border-radius: 2px;
  min-width: 3px;
  transition: width 0.3s;
}

.trace-chain-tree-table .row-error .duration-bar {
  background: #f56c6c;
}

.chain-flow {
  display: flex;
  align-items: flex-start;
  overflow-x: auto;
  gap: 0;
  padding: 16px 0;
}

.chain-node-wrapper {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.chain-node {
  width: 200px;
  border: 2px solid #e4e7ed;
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.2s;
  background: white;
}

.chain-node:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.2);
}

.chain-node.node-active {
  border-color: #409eff;
  background: #ecf5ff;
}

.chain-node.node-error {
  border-color: #f56c6c;
  background: #fef0f0;
}

.node-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.node-service {
  font-size: 14px;
  font-weight: 600;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 120px;
}

.node-status {
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 10px;
  font-weight: 500;
}

.node-status.success {
  background: #f0f9eb;
  color: #67c23a;
}

.node-status.error {
  background: #fef0f0;
  color: #f56c6c;
}

.node-path {
  font-size: 12px;
  color: #1890ff;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-bottom: 8px;
  cursor: pointer;
  padding: 2px 4px;
  border-radius: 3px;
  transition: background 0.2s;
}

.node-path:hover {
  background: #e6f7ff;
}

.node-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #999;
}

.chain-arrow {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 0 8px;
  position: relative;
  min-width: 60px;
}

.arrow-line {
  width: 40px;
  height: 2px;
  background: #dcdfe6;
  margin-bottom: 4px;
}

.arrow-duration {
  font-size: 11px;
  color: #999;
  white-space: nowrap;
}

.arrow-head {
  color: #dcdfe6;
  font-size: 12px;
  margin-top: -14px;
}

.node-detail {
  background: white;
  border-radius: 4px;
  margin-top: 16px;
  overflow: hidden;
  border: 1px solid #e4e7ed;
}

.detail-header {
  padding: 12px 24px;
  background: #fafafa;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-title {
  font-size: 14px;
  font-weight: 600;
  color: #333;
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
}

.log-item:last-child {
  margin-bottom: 0;
}

.log-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.log-time {
  font-size: 12px;
  color: #999;
  font-family: monospace;
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
</style>
