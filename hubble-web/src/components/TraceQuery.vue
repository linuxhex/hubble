<template>
  <div class="trace-query">
    <!-- 搜索条件区域 -->
    <div class="search-area">
      <el-form :model="queryForm" inline class="search-form">
        <el-form-item label="业务" class="no-margin" required>
          <el-select
            v-model="queryForm.traceId"
            placeholder="请选择业务"
            filterable
            size="small"
            style="width: 200px"
            @change="handleTraceChange"
          >
            <el-option
              v-for="trace in traceList"
              :key="trace.id"
              :label="trace.name"
              :value="trace.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="时间范围" class="no-margin" required>
          <el-date-picker
            v-model="timeRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="X"
            size="small"
            style="width: 300px"
          />
        </el-form-item>

        <el-form-item
          v-for="(variable, index) in variables"
          :key="index"
          :label="variable.name"
          class="no-margin"
          :required="variable.required"
        >
          <el-input
            v-model="queryForm.variables[variable.name]"
            :placeholder="`请输入${variable.name}`"
            clearable
            size="small"
            style="width: 150px"
          />
        </el-form-item>

        <el-form-item class="no-margin operation-buttons">
          <el-button type="primary" size="small" @click="handleQuery" :loading="querying">
            查询
          </el-button>
          <el-button size="small" @click="handleReset">重置</el-button>
          <el-button
            v-if="hasResults"
            size="small"
            @click="handleExport"
            :disabled="querying"
          >
            导出结果
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 查询结果 -->
    <div v-if="hasResults" class="results-area">
      <div
        v-for="(result, index) in sortedQueryResults"
        :key="index"
        class="result-card"
        :class="{
          'result-success': result.status === 'success',
          'result-failed': result.status === 'failed',
          'result-timeout': result.status === 'timeout'
        }"
      >
        <div class="result-header">
          <span class="node-name">{{ result.nodeName }}</span>
          <el-tag
            :type="
              result.logCount === 0
                ? 'info'
                : result.status === 'success'
                ? 'success'
                : result.status === 'failed'
                ? 'danger'
                : 'warning'
            "
            size="small"
          >
            {{ result.logCount === 0 ? '无日志' : getStatusText(result.status) }}
          </el-tag>
          <span class="log-count">日志数: {{ result.logCount }}</span>
          <el-button
            v-if="result.hasChildren"
            link
            type="primary"
            size="small"
            @click="handleDrillDown(result)"
            :loading="result.showChildren && childrenLoading[result.nodeId]"
          >
            <el-icon><ArrowDown v-if="!result.showChildren" /><ArrowUp v-else /></el-icon>
            {{ result.showChildren ? '收起' : '下钻' }}
          </el-button>
        </div>

        <div v-if="result.status === 'failed'" class="error-message">
          <el-alert :title="result.error" type="error" :closable="false" />
        </div>

        <LogViewer
          v-if="result.logs && result.logs.length > 0"
          :logs="result.logs"
          :start-time="queryForm.timeRange.from"
          :end-time="queryForm.timeRange.to"
          :logstore="result.slsLogstore"
        />

        <!-- 子节点结果 -->
        <div v-if="result.showChildren && result.children" class="child-nodes">
          <el-divider />
          <h4 class="child-nodes-title">子节点</h4>
          <div
            v-for="(childResult, childIndex) in result.children"
            :key="childIndex"
            class="child-result-card"
            :class="{
              'result-success': childResult.status === 'success',
              'result-failed': childResult.status === 'failed',
              'result-timeout': childResult.status === 'timeout'
            }"
          >
            <div class="result-header">
              <span class="node-name">{{ childResult.nodeName }}</span>
              <el-tag
                :type="
                  childResult.logCount === 0
                    ? 'info'
                    : childResult.status === 'success'
                    ? 'success'
                    : childResult.status === 'failed'
                    ? 'danger'
                    : 'warning'
                "
                size="small"
              >
                {{ childResult.logCount === 0 ? '无日志' : getStatusText(childResult.status) }}
              </el-tag>
              <span class="log-count">日志数: {{ childResult.logCount }}</span>
            </div>

            <div v-if="childResult.status === 'failed'" class="error-message">
              <el-alert :title="childResult.error" type="error" :closable="false" />
            </div>

            <LogViewer
              v-if="childResult.logs && childResult.logs.length > 0"
              :logs="childResult.logs"
              :start-time="queryForm.timeRange.from"
              :end-time="queryForm.timeRange.to"
              :logstore="childResult.slsLogstore"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- 空态引导 -->
    <div v-if="!hasResults && !querying" class="empty-area">
      <el-empty description="暂无查询结果">
        <div class="empty-guide">
          <p>按以下步骤开始查询：</p>
          <p>1. 在上方选择要查询的业务链路（当前已配置 {{ traceList.length }} 条）</p>
          <p>2. 填写必填查询变量（如订单号、用户 ID 等）</p>
          <p>3. 确认时间范围后点击「查询」，支持结果导出与逐节点下钻</p>
        </div>
      </el-empty>
    </div>

    <!-- 查询中提示 -->
    <div v-if="querying" class="querying-area">
      <el-result icon="loading" title="查询中..." sub-title="请稍候" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown, ArrowUp } from '@element-plus/icons-vue'
import { getTraceList, getTraceVariables } from '@/api/trace-management'
import { queryLogsStream, queryChildNodesStream, exportQueryResults } from '@/api/trace-query'
import LogViewer from '@/components/LogViewer.vue'
import { ensureSecondTimestamp } from '@/utils/timestamp'

// 计算默认时间范围（一周前到今天）
const getDefaultTimeRange = () => {
  const now = Date.now()
  const today = new Date(now)
  today.setHours(0, 0, 0, 0)
  const weekAgo = new Date(today)
  weekAgo.setDate(weekAgo.getDate() - 7)
  
  return {
    from: Math.floor(weekAgo.getTime() / 1000),
    to: Math.floor(today.getTime() / 1000) + 86400 - 1
  }
}

const defaultTimeRange = getDefaultTimeRange()

const queryForm = ref({
  traceId: null,
  variables: {},
  timeRange: defaultTimeRange
})

const timeRange = ref([
  defaultTimeRange.from,
  defaultTimeRange.from + 7 * 86400
])

const traceList = ref([])
const variables = ref([])
const querying = ref(false)
const queryResults = ref([])
const abortController = ref(null)
const childrenLoading = ref({})

const hasResults = computed(() => queryResults.value.length > 0)

const parseTime = (timeStr) => {
  const timeNum = parseInt(timeStr)
  if (!isNaN(timeNum) && timeNum.toString() === timeStr.trim()) {
    return timeNum * 1000
  }
  const date = new Date(timeStr)
  return date.getTime()
}

const sortNodeResults = (results) => {
  if (!results || results.length === 0) {
    return []
  }
  
  const resultsCopy = [...results]
  
  return resultsCopy.sort((a, b) => {
    const timeA = a.logs && a.logs.length > 0 ? a.logs[0].time : null
    const timeB = b.logs && b.logs.length > 0 ? b.logs[0].time : null
    
    if (timeA && timeB) {
      const timestampA = parseTime(timeA)
      const timestampB = parseTime(timeB)
      
      if (!isNaN(timestampA) && !isNaN(timestampB)) {
        return timestampA - timestampB
      }
      return timeA.localeCompare(timeB)
    }
    
    if (!timeA && !timeB) {
      const orderA = a.nodeOrder ?? 999
      const orderB = b.nodeOrder ?? 999
      return orderA - orderB
    }
    
    if (!timeA) return 1
    if (!timeB) return -1
    
    return 0
  })
}

const sortedQueryResults = computed(() => {
  return sortNodeResults(queryResults.value)
})

const getStatusText = (status) => {
  const statusMap = {
    success: '成功',
    failed: '失败',
    timeout: '超时'
  }
  return statusMap[status] || status
}

const loadTraceList = async () => {
  try {
    const res = await getTraceList({ page: 1, pageSize: 100 })
    traceList.value = res.data.list || []
  } catch (error) {
    ElMessage.error('加载业务链路列表失败')
  }
}

const handleTraceChange = async (traceId) => {
  if (!traceId) {
    variables.value = []
    queryForm.value.variables = {}
    return
  }

  try {
    const res = await getTraceVariables(traceId)
    const varNames = res.data || []
    
    variables.value = varNames.map((name) => ({
      name,
      required: true
    }))
    
    queryForm.value.variables = {}
    varNames.forEach((name) => {
      queryForm.value.variables[name] = ''
    })
  } catch (error) {
    ElMessage.error('获取查询变量失败')
  }
}

const handleQuery = () => {
  if (!queryForm.value.traceId) {
    ElMessage.warning('请选择业务链路')
    return
  }

  if (!timeRange.value || timeRange.value.length !== 2) {
    ElMessage.warning('请选择时间范围')
    return
  }

  let from = ensureSecondTimestamp(timeRange.value[0])
  let to = ensureSecondTimestamp(timeRange.value[1])
  
  from = Number(from)
  to = Number(to) + 86400 - 1
  
  queryForm.value.timeRange = {
    from,
    to
  }

  const missingVars = variables.value.filter(
    (v) => v.required && !queryForm.value.variables[v.name]
  )
  if (missingVars.length > 0) {
    ElMessage.warning(`请填写必填变量: ${missingVars.map((v) => v.name).join(', ')}`)
    return
  }

  queryResults.value = []
  querying.value = true

  if (abortController.value) {
    abortController.value.abort()
  }

  abortController.value = queryLogsStream(
    queryForm.value,
    (result) => {
      const index = queryResults.value.findIndex(
        (r) => r.nodeName === result.nodeName
      )
      if (index >= 0) {
        queryResults.value[index] = result
      } else {
        queryResults.value.push(result)
      }
    },
    (error) => {
      ElMessage.error('查询失败: ' + error.message)
      querying.value = false
    },
    () => {
      querying.value = false
      ElMessage.success('查询完成')
    }
  )
}

const handleReset = () => {
  const defaultRange = getDefaultTimeRange()
  queryForm.value = {
    traceId: null,
    variables: {},
    timeRange: defaultRange
  }
  timeRange.value = [
    defaultRange.from,
    defaultRange.from + 7 * 86400
  ]
  variables.value = []
  queryResults.value = []
  querying.value = false
  
  if (abortController.value) {
    abortController.value.abort()
    abortController.value = null
  }
}

const handleExport = async () => {
  if (!hasResults.value) {
    ElMessage.warning('暂无查询结果可导出')
    return
  }

  try {
    const blob = await exportQueryResults(queryForm.value)
    
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `query_results_${Date.now()}.csv`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    
    ElMessage.success('导出成功')
  } catch (error) {
    ElMessage.error('导出失败: ' + (error.message || '未知错误'))
  }
}

const handleDrillDown = (result) => {
  if (!result.nodeId) {
    ElMessage.warning('节点ID不存在')
    return
  }

  result.showChildren = !result.showChildren

  if (result.showChildren && (!result.children || result.children.length === 0)) {
    childrenLoading.value[result.nodeId] = true
    result.children = []

    queryChildNodesStream(
      result.nodeId,
      queryForm.value,
      (childResult) => {
        if (!result.children) {
          result.children = []
        }
        const index = result.children.findIndex((r) => r.nodeName === childResult.nodeName)
        if (index >= 0) {
          result.children[index] = childResult
        } else {
          result.children.push(childResult)
        }
      },
      (error) => {
        ElMessage.error('查询子节点失败: ' + error.message)
        childrenLoading.value[result.nodeId] = false
      },
      () => {
        if (result.children) {
          result.children = sortNodeResults(result.children)
        }
        childrenLoading.value[result.nodeId] = false
        ElMessage.success('子节点查询完成')
      }
    )
  }
}

onMounted(() => {
  loadTraceList()
})

onUnmounted(() => {
  if (abortController.value) {
    abortController.value.abort()
  }
})
</script>

<style scoped>
.trace-query {
  padding: 16px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  background: #f5f7fa;
}

.search-area {
  background: white;
  padding: 12px 24px;
  border-radius: 4px;
}

.search-form {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
}

.no-margin {
  margin: 0 !important;
}

.operation-buttons {
  margin-left: auto !important;
  white-space: nowrap;
}

.results-area {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.result-card {
  background: white;
  border-radius: 4px;
  padding: 16px;
  border-left: 4px solid #e4e7ed;
}

.result-card.result-success {
  border-left-color: #67c23a;
}

.result-card.result-failed {
  border-left-color: #f56c6c;
}

.result-card.result-timeout {
  border-left-color: #e6a23c;
}

.result-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.node-name {
  font-weight: bold;
  font-size: 14px;
}

.log-count {
  margin-left: auto;
  color: #909399;
  font-size: 12px;
}

.error-message {
  margin-bottom: 12px;
}

.child-nodes {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid #e4e7ed;
}

.child-nodes-title {
  margin-bottom: 12px;
  color: #606266;
  font-size: 14px;
  font-weight: 500;
}

.child-result-card {
  background: #f8f9fa;
  border-radius: 4px;
  padding: 12px;
  margin-bottom: 12px;
  margin-left: 20px;
  border-left: 3px solid #409eff;
}

.child-result-card.result-success {
  border-left-color: #95d475;
}

.child-result-card.result-failed {
  border-left-color: #f89898;
}

.child-result-card.result-timeout {
  border-left-color: #f3c174;
}

.querying-area {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: white;
  border-radius: 4px;
  min-height: 300px;
}

.empty-area {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: white;
  border-radius: 4px;
  min-height: 300px;
}

.empty-guide {
  text-align: left;
  color: #606266;
  font-size: 13px;
  line-height: 2;
}

.empty-guide p:first-child {
  font-weight: 600;
  color: #303133;
}

:deep(.el-form-item__label) {
  font-size: 12px;
  color: #606266;
  padding-right: 12px !important;
}

:deep(.el-button--small) {
  padding: 5px 11px;
  font-size: 12px;
}
</style>


