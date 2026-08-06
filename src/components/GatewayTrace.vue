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
      <p>请输入链路ID查询链路详情</p>
      <p style="font-size: 12px; color: #999; margin-top: 8px;">链路ID可在日志搜索、异常大盘等处获取</p>
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

      <div class="chain-flow">
        <div
          v-for="(node, index) in nodes"
          :key="index"
          class="chain-node-wrapper"
        >
          <div
            class="chain-node"
            :class="{ 'node-error': node.status === 'error', 'node-active': selectedNode === index }"
            @click="selectNode(index)"
          >
            <div class="node-header">
              <span class="node-service">{{ node.serviceName }}</span>
              <span class="node-status" :class="node.status">{{ node.status === 'error' ? '异常' : '正常' }}</span>
            </div>
            <div class="node-path">{{ node.apiPath || '--' }}</div>
            <div class="node-stats">
              <div class="stat-item">
                <span class="stat-label">耗时</span>
                <span class="stat-value" :class="{'stat-slow': node.duration > 1000}">{{ node.duration }}ms</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">日志</span>
                <span class="stat-value">{{ node.logCount }}</span>
              </div>
            </div>
            <div class="node-meta">
              <span class="node-time">{{ node.formattedTime }}</span>
            </div>
          </div>
          <div v-if="index < nodes.length - 1" class="chain-arrow">
            <div class="arrow-line"></div>
            <div class="arrow-duration">{{ node.duration }}ms</div>
            <div class="arrow-head">▶</div>
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
import { getTraceChain } from '@/api/trace-chain.js'

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

const totalDuration = computed(() => {
  return nodes.value.reduce((sum, node) => sum + (node.duration || 0), 0)
})

const errorCount = computed(() => {
  return nodes.value.filter(node => node.status === 'error').length
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
    nodes.value = data.nodes || []
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

const selectNode = (index) => {
  selectedNode.value = selectedNode.value === index ? null : index
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

.trace-chain {
  flex: 1;
  overflow: auto;
}

.chain-header {
  background: white;
  padding: 12px 24px;
  border-radius: 4px;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.chain-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.chain-meta {
  font-size: 12px;
  color: #999;
}

.trace-summary {
  background: white;
  padding: 16px 24px;
  border-radius: 4px;
  margin-bottom: 16px;
  display: flex;
  gap: 32px;
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

.summary-value {
  font-size: 20px;
  font-weight: 600;
  color: #333;
}

.summary-value.summary-error {
  color: #f56c6c;
}

.chain-flow {
  background: white;
  padding: 24px;
  border-radius: 4px;
  display: flex;
  align-items: flex-start;
  overflow-x: auto;
  gap: 0;
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

.node-stats {
  display: flex;
  gap: 12px;
  margin-bottom: 8px;
  padding: 6px 0;
  border-top: 1px solid #f0f0f0;
  border-bottom: 1px solid #f0f0f0;
}

.stat-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat-label {
  font-size: 10px;
  color: #999;
}

.stat-value {
  font-size: 13px;
  font-weight: 600;
  color: #333;
}

.stat-value.stat-slow {
  color: #f56c6c;
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
  padding: 0 12px;
  position: relative;
  min-width: 80px;
  justify-content: center;
}

.arrow-line {
  width: 50px;
  height: 2px;
  background: linear-gradient(to right, #dcdfe6, #c0c4cc);
  margin-bottom: 6px;
  position: relative;
}

.arrow-line::after {
  content: '';
  position: absolute;
  right: -2px;
  top: -3px;
  width: 0;
  height: 0;
  border-left: 6px solid #c0c4cc;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
}

.arrow-duration {
  font-size: 12px;
  color: #666;
  font-weight: 500;
  white-space: nowrap;
  background: white;
  padding: 2px 6px;
  border-radius: 10px;
  border: 1px solid #e4e7ed;
}

.arrow-head {
  display: none;
}

.node-detail {
  background: white;
  border-radius: 4px;
  margin-top: 16px;
  overflow: hidden;
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
</style>
