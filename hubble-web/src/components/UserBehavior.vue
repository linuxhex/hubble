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
      <el-table :data="items" style="width: 100%" border>
        <el-table-column prop="formattedDateTime" label="时间" width="180" />
        <el-table-column prop="pageName" label="页面名称" width="200" show-overflow-tooltip />
        <el-table-column prop="url" label="接口路径" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="url-text">{{ row.url || '--' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="terminal" label="终端" width="100" />
        <el-table-column label="链路ID" width="200">
          <template #default="{ row }">
            <el-button
              v-if="row.trace"
              type="primary"
              link
              @click="toggleTraceExpand(row)"
            >
              {{ expandedTraces[row.trace] ? '收起' : '展开链路' }}
            </el-button>
            <span v-else>--</span>
          </template>
        </el-table-column>
        <el-table-column prop="responseStatus" label="响应状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.responseStatus === '200' ? 'success' : 'danger'" size="small">
              {{ row.responseStatus || '--' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div v-for="item in items" :key="'trace-' + item.trace" class="trace-expand-area" v-show="item.trace && expandedTraces[item.trace]">
        <div v-if="item.trace && expandedTraces[item.trace]" class="trace-chain-container">
          <div v-if="traceData[item.trace]?.loading" class="trace-loading">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>加载链路数据...</span>
          </div>
          <div v-else-if="traceData[item.trace]?.nodes?.length > 0" class="trace-chain">
            <div class="chain-flow">
              <div
                v-for="(node, index) in traceData[item.trace].nodes"
                :key="index"
                class="chain-node-wrapper"
              >
                <div
                  class="chain-node"
                  :class="{ 'node-error': node.status === 'error', 'node-active': traceData[item.trace]?.selectedNode === index }"
                  @click="selectTraceNode(item.trace, index)"
                >
                  <div class="node-header">
                    <span class="node-service">{{ node.serviceName }}</span>
                    <span class="node-status" :class="node.status">{{ node.status === 'error' ? '异常' : '正常' }}</span>
                  </div>
                  <div class="node-path">{{ node.apiPath || '--' }}</div>
                  <div class="node-meta">
                    <span class="node-time">{{ node.formattedTime }}</span>
                    <span class="node-logs">{{ node.logCount }} 条日志</span>
                  </div>
                </div>
                <div v-if="index < traceData[item.trace].nodes.length - 1" class="chain-arrow">
                  <div class="arrow-line"></div>
                  <div class="arrow-duration">{{ node.duration }}ms</div>
                  <div class="arrow-head">▶</div>
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
</template>

<script setup>
import { ref, reactive } from 'vue'
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
      traceData[traceId] = {
        loading: false,
        nodes: data.nodes || [],
        selectedNode: null
      }
    } catch (e) {
      console.error('获取链路数据失败:', e)
      traceData[traceId] = { loading: false, nodes: [], selectedNode: null }
    }
  }
}

const selectTraceNode = (traceId, index) => {
  if (traceData[traceId]) {
    traceData[traceId].selectedNode = traceData[traceId].selectedNode === index ? null : index
  }
}

selectedDate.value = getTodayDate()
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
