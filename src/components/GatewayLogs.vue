<template>
  <div class="gateway-logs">
    <!-- 搜索条件区域 -->
    <div class="search-area">
      <el-form :model="searchForm" inline class="search-form">
        <el-form-item label="应用名称" class="no-margin">
          <el-input v-model="searchForm.appName" placeholder="cargo-ltl-app" size="small" style="width: 160px" />
        </el-form-item>
        <el-form-item label="请求URL" class="no-margin">
          <el-input v-model="searchForm.url" placeholder="URL: example.ymm-xxx-app/xxx" size="small" style="width: 300px" />
        </el-form-item>
        <el-form-item label="手机号" class="no-margin">
          <el-input v-model="searchForm.phone" placeholder="请输入手机号" size="small" style="width: 120px" />
        </el-form-item>
        <el-form-item label="用户ID" class="no-margin">
          <el-input v-model="searchForm.userId" placeholder="请输入用户ID" size="small" style="width: 120px" />
        </el-form-item>
        <el-form-item label="链路ID" class="no-margin">
          <el-input v-model="searchForm.traceId" placeholder="请输入链路ID" size="small" style="width: 220px" />
        </el-form-item>
        <el-form-item label="时间范围" class="no-margin">
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            format="YYYY-MM-DD HH:mm:ss"
            :shortcuts="timeRangeShortcuts"
            size="small"
            style="width: 340px"
          />
        </el-form-item>
        <el-form-item class="no-margin operation-buttons">
          <el-button type="primary" size="small" @click="handleQuery">查询</el-button>
          <el-button type="primary" size="small" @click="handleQueryAll">查全网</el-button>
          <el-button size="small" circle @click="handleRefresh">
            <el-icon><Refresh /></el-icon>
          </el-button>
          <el-tooltip content="日志分布" placement="top">
            <el-button size="small" circle @click="showLogDistribution">
              <el-icon><Histogram /></el-icon>
            </el-button>
          </el-tooltip>
          <el-tooltip content="状态码分布" placement="top">
            <el-button size="small" circle @click="showStatusDistribution">
              <el-icon><PieChart /></el-icon>
            </el-button>
          </el-tooltip>
        </el-form-item>
      </el-form>
    </div>

    <!-- 日志分布弹窗 -->
    <el-dialog
      v-model="logDistributionVisible"
      title="日志分布"
      width="600px"
      destroy-on-close
    >
      <div ref="logChartRef" style="height: 400px"></div>
    </el-dialog>

    <!-- 状态码分布弹窗 -->
    <el-dialog
      v-model="statusDistributionVisible"
      title="状态码分布"
      width="600px"
      destroy-on-close
    >
      <div ref="statusChartRef" style="height: 400px"></div>
    </el-dialog>

    <!-- 日志列表 -->
    <div class="log-list">
      <div class="list-header">
        <span>共搜索{{ totalCount }}条数据</span>
      </div>
      <el-table :data="logs" style="width: 100%" size="small" border class="compact-table" v-loading="loading">
        <el-table-column prop="appName" label="项目名" width="160" show-overflow-tooltip />
        <el-table-column prop="serverIp" label="服务器IP" width="120" show-overflow-tooltip />
        <el-table-column prop="url" label="URL" min-width="300" show-overflow-tooltip>
          <template #default="scope">
            <el-button 
              type="primary" 
              link 
              @click="handleUrlClick(scope.row)"
            >{{ scope.row.url || '--' }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="userId" label="用户ID" width="120" show-overflow-tooltip>
          <template #default="scope">
            {{ scope.row.userId || '--' }}
          </template>
        </el-table-column>
        <el-table-column prop="duration" label="耗时(ms)" width="100" sortable>
          <template #default="scope">
            <span>{{ typeof scope.row.duration === 'number' && scope.row.duration > 0 ? scope.row.duration.toFixed(2) : '--' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="timestamp" label="发生时间" width="180" />
        <el-table-column prop="statusCode" label="状态码" width="80">
          <template #default="scope">
            <span :class="{ 'success-status': scope.row.statusCode === 200, 'error-status': scope.row.statusCode >= 400 }">{{ scope.row.statusCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="下游依赖" width="180" show-overflow-tooltip>
          <template #default="scope">
            <span v-if="scope.row.downstreamService" class="downstream-tag">{{ scope.row.downstreamService }}</span>
            <span v-else class="text-muted">--</span>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="120">
          <template #default="scope">
            <div class="operation-cell">
              <el-button 
                type="text" 
                size="small" 
                @click="handleTraceClick(scope.row)"
              >链路</el-button>
              <el-divider direction="vertical" />
              <el-button 
                type="text" 
                size="small"
                @click="handleDetailClick(scope.row)"
              >详情</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- HTTP详情弹窗 -->
    <el-dialog
      v-model="httpDetailVisible"
      title="接口详情"
      width="800px"
      destroy-on-close
      class="http-detail-dialog"
    >
      <div class="http-detail">
        <!-- 基本信息部分，仅在详情按钮点击时显示 -->
        <template v-if="showFullDetail">
          <div class="section">
            <div class="section-header">
              <div class="header-content">
                <el-icon><Document /></el-icon>
                <span class="title">基本信息</span>
              </div>
            </div>
            <div class="section-content">
              <div class="info-item">
                <span class="label">请求方式：</span>
                <span class="value method">{{ httpDetail.method }}</span>
              </div>
              <div class="info-item">
                <span class="label">请求路径：</span>
                <span class="value">{{ httpDetail.path }}</span>
              </div>
              <div class="info-item">
                <span class="label">请求来源：</span>
                <span class="value source">{{ httpDetail.source }}</span>
              </div>
              <div class="info-item">
                <span class="label">请求时间：</span>
                <span class="value">{{ httpDetail.timestamp }}</span>
              </div>
              <div class="info-item">
                <span class="label">响应状态：</span>
                <span class="value status" :class="httpDetail.status === 200 ? 'success' : 'error'">
                  {{ httpDetail.status }}
                </span>
              </div>
              <div class="info-item">
                <span class="label">接口耗时：</span>
                <span class="value duration">{{ httpDetail.duration }}ms</span>
              </div>
            </div>
          </div>
        </template>

        <!-- 请求参数部分 -->
        <div class="section">
          <div class="section-header request">
            <div class="header-content">
              <el-icon><Upload /></el-icon>
              <span class="title">请求参数</span>
            </div>
          </div>
          <div class="section-content">
            <pre class="json-content">{{ httpDetail.requestBody }}</pre>
          </div>
        </div>

        <!-- 返回结果部分 -->
        <div class="section">
          <div class="section-header response">
            <div class="header-content">
              <el-icon><Download /></el-icon>
              <span class="title">返回结果</span>
            </div>
          </div>
          <div class="section-content">
            <pre class="json-content">{{ httpDetail.responseBody }}</pre>
          </div>
        </div>

        <!-- 请求头部分，仅在详情按钮点击时显示 -->
        <template v-if="showFullDetail">
          <div class="section">
            <div class="section-header request">
              <div class="title-with-action">
                <div class="header-content">
                  <el-icon><Document /></el-icon>
                  <span class="title">请求头</span>
                </div>
                <el-button 
                  type="primary" 
                  link 
                  @click="httpDetail.showRequestHeaders = !httpDetail.showRequestHeaders"
                >
                  {{ httpDetail.showRequestHeaders ? '收起' : '展开' }}
                  <el-icon class="header-icon" :class="{ 'is-active': httpDetail.showRequestHeaders }">
                    <ArrowDown />
                  </el-icon>
                </el-button>
              </div>
            </div>
            <div class="section-content" v-show="httpDetail.showRequestHeaders">
              <pre class="json-content">{{ httpDetail.requestHeaders }}</pre>
            </div>
          </div>

          <!-- 响应头部分，仅在详情按钮点击时显示 -->
          <div class="section">
            <div class="section-header response">
              <div class="title-with-action">
                <div class="header-content">
                  <el-icon><Document /></el-icon>
                  <span class="title">响应头</span>
                </div>
                <el-button 
                  type="primary" 
                  link 
                  @click="httpDetail.showResponseHeaders = !httpDetail.showResponseHeaders"
                >
                  {{ httpDetail.showResponseHeaders ? '收起' : '展开' }}
                  <el-icon class="header-icon" :class="{ 'is-active': httpDetail.showResponseHeaders }">
                    <ArrowDown />
                  </el-icon>
                </el-button>
              </div>
            </div>
            <div class="section-content" v-show="httpDetail.showResponseHeaders">
              <pre class="json-content">{{ httpDetail.responseHeaders }}</pre>
            </div>
          </div>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import { Refresh, Histogram, PieChart, Upload, Download, Document, ArrowDown } from '@element-plus/icons-vue'
import { useRouter, useRoute } from 'vue-router'
import * as echarts from 'echarts'
import { queryGatewayLogs } from '@/api/keyword-log-query.js'

const router = useRouter()
const route = useRoute()

const searchForm = reactive({
  appName: '',
  url: '',
  phone: '',
  userId: '',
  traceId: '',
  keyword: ''
})

const getDefaultTimeRange = () => {
  const end = new Date()
  const start = new Date()
  start.setTime(start.getTime() - 60 * 60 * 1000)
  const fmt = (d) => {
    const p = (n) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  }
  return [fmt(start), fmt(end)]
}

const timeRange = ref(getDefaultTimeRange())

const timeRangeShortcuts = [
  { text: '近15分钟', value: () => { const e = new Date(); const s = new Date(e.getTime() - 15*60*1000); const fmt = d => `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}:${String(d.getSeconds()).padStart(2,'0')}`; return [fmt(s), fmt(e)] } },
  { text: '近1小时', value: () => { const e = new Date(); const s = new Date(e.getTime() - 60*60*1000); const fmt = d => `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}:${String(d.getSeconds()).padStart(2,'0')}`; return [fmt(s), fmt(e)] } },
  { text: '近6小时', value: () => { const e = new Date(); const s = new Date(e.getTime() - 6*60*60*1000); const fmt = d => `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}:${String(d.getSeconds()).padStart(2,'0')}`; return [fmt(s), fmt(e)] } },
  { text: '近1天', value: () => { const e = new Date(); const s = new Date(e.getTime() - 24*60*60*1000); const fmt = d => `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}:${String(d.getSeconds()).padStart(2,'0')}`; return [fmt(s), fmt(e)] } }
]

const handleTraceClick = (row) => {
  router.push({
    path: '/gateway/trace',
    query: {
      traceId: row.traceId,
      appName: row.appName,
      timestamp: row.timestamp
    }
  })
}

const logs = ref([])
const totalCount = ref(0)
const loading = ref(false)

const fetchLogs = async () => {
  loading.value = true
  try {
    const params = {
      page: 1,
      pageSize: 50
    }
    if (timeRange.value && timeRange.value.length === 2) {
      params.startTime = timeRange.value[0]
      params.endTime = timeRange.value[1]
    }
    if (searchForm.keyword) {
      // 如果同时有 appName 和 keyword，组合成完整的 SLS 查询
      if (searchForm.appName) {
        params.keyword = `__tag__:_container_name_: ${searchForm.appName} and ${searchForm.keyword}`
      } else {
        params.keyword = searchForm.keyword
      }
    } else {
      if (searchForm.appName) params.appName = searchForm.appName
      if (searchForm.url) params.url = searchForm.url
      if (searchForm.phone) params.phone = searchForm.phone
      if (searchForm.userId) params.userId = searchForm.userId
      if (searchForm.traceId) params.traceId = searchForm.traceId
    }

    const res = await queryGatewayLogs(params)
    const data = res?.data || res
    logs.value = data?.list || data?.records || data || []
    totalCount.value = data?.total || logs.value.length
  } catch (e) {
    console.error('查询日志失败:', e)
    logs.value = []
    totalCount.value = 0
  } finally {
    loading.value = false
  }
}

const handleQuery = () => {
  fetchLogs()
}

const handleQueryAll = () => {
  searchForm.appName = ''
  searchForm.url = ''
  searchForm.phone = ''
  searchForm.userId = ''
  searchForm.traceId = ''
  searchForm.keyword = ''
  timeRange.value = getDefaultTimeRange()
  fetchLogs()
}

const handleRefresh = () => {
  fetchLogs()
}

onMounted(() => {
  // 读取路由参数，预填充搜索表单
  if (route.query.appName) {
    searchForm.appName = route.query.appName
  }
  if (route.query.keyword) {
    // 从异常大盘跳转时，使用keyword作为搜索条件
    searchForm.keyword = route.query.keyword
  }
  if (route.query.level === 'ERROR') {
    // 如果指定了ERROR级别，可以在搜索时添加level过滤
    // 暂时不处理level，后续可以扩展
  }
  // 从异常大盘跳转时，自动触发搜索
  if (route.query.appName || route.query.keyword) {
    fetchLogs()
  }
})

const logDistributionVisible = ref(false)
const statusDistributionVisible = ref(false)
const logChartRef = ref(null)
const statusChartRef = ref(null)
let logChart = null
let statusChart = null

const showLogDistribution = () => {
  logDistributionVisible.value = true
  setTimeout(() => {
    if (!logChartRef.value) return
    if (!logChart) {
      logChart = echarts.init(logChartRef.value)
    }

    const hourBuckets = {}
    logs.value.forEach(log => {
      const ts = log.timestamp || ''
      const hour = ts.length >= 13 ? ts.substring(0, 13) + ':00' : '未知'
      hourBuckets[hour] = (hourBuckets[hour] || 0) + 1
    })

    const sortedHours = Object.keys(hourBuckets).sort()
    const option = {
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
      xAxis: { type: 'category', data: sortedHours },
      yAxis: { type: 'value' },
      series: [{ name: '请求数', type: 'bar', data: sortedHours.map(h => hourBuckets[h]), itemStyle: { color: '#409EFF' } }]
    }
    logChart.setOption(option)
  })
}

const showStatusDistribution = () => {
  statusDistributionVisible.value = true
  setTimeout(() => {
    if (!statusChartRef.value) return
    if (!statusChart) {
      statusChart = echarts.init(statusChartRef.value)
    }

    const statusBuckets = {}
    logs.value.forEach(log => {
      const code = String(log.statusCode || '未知')
      statusBuckets[code] = (statusBuckets[code] || 0) + 1
    })

    const colorMap = { '200': '#67C23A', '404': '#E6A23C', '500': '#F56C6C', '403': '#909399' }
    const option = {
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { orient: 'vertical', left: 'left' },
      series: [{
        type: 'pie', radius: '70%',
        data: Object.entries(statusBuckets).map(([name, value]) => ({
          value, name, itemStyle: { color: colorMap[name] || '#909399' }
        })),
        emphasis: { itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0, 0, 0, 0.5)' } }
      }]
    }
    statusChart.setOption(option)
  })
}

watch([logDistributionVisible, statusDistributionVisible], ([newLogVisible, newStatusVisible], [oldLogVisible, oldStatusVisible]) => {
  if (!newLogVisible && oldLogVisible) { logChart?.dispose(); logChart = null }
  if (!newStatusVisible && oldStatusVisible) { statusChart?.dispose(); statusChart = null }
})

const handleDetailClick = (row) => {
  showFullDetail.value = true
  httpDetailVisible.value = true
  httpDetail.method = row.method || 'POST'
  httpDetail.path = row.url
  httpDetail.source = row.message
  httpDetail.timestamp = row.timestamp
  httpDetail.status = row.statusCode
  httpDetail.duration = row.duration

  httpDetail.requestBody = JSON.stringify({
    userId: row.userId,
    traceId: row.traceId,
    appName: row.appName,
    serverIp: row.serverIp
  }, null, 2)

  httpDetail.responseBody = JSON.stringify({
    statusCode: row.statusCode,
    duration: row.duration,
    message: row.message
  }, null, 2)

  httpDetail.requestHeaders = JSON.stringify({
    "Content-Type": "application/json",
    "X-Trace-ID": row.traceId || ''
  }, null, 2)

  httpDetail.responseHeaders = JSON.stringify({
    "Content-Type": "application/json;charset=UTF-8",
    "X-Response-Time": `${row.duration}ms`
  }, null, 2)

  httpDetail.showRequestHeaders = false
  httpDetail.showResponseHeaders = false
}

const httpDetailVisible = ref(false)
const showFullDetail = ref(false)
const httpDetail = reactive({
  method: '',
  path: '',
  source: '',
  timestamp: '',
  status: 200,
  duration: '',
  requestBody: '',
  responseBody: '',
  requestHeaders: '',
  responseHeaders: '',
  showRequestHeaders: false,
  showResponseHeaders: false
})

const handleUrlClick = (row) => {
  showFullDetail.value = false
  httpDetailVisible.value = true
  httpDetail.requestBody = JSON.stringify({ url: row.url, userId: row.userId, traceId: row.traceId }, null, 2)
  httpDetail.responseBody = JSON.stringify({ statusCode: row.statusCode, duration: row.duration }, null, 2)
  httpDetail.method = ''
  httpDetail.path = ''
  httpDetail.source = ''
  httpDetail.timestamp = ''
  httpDetail.status = 200
  httpDetail.duration = ''
  httpDetail.requestHeaders = ''
  httpDetail.responseHeaders = ''
  httpDetail.showRequestHeaders = false
  httpDetail.showResponseHeaders = false
}
</script>

<style scoped>
.gateway-logs {
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
  flex-wrap: nowrap;
  gap: 8px;
}

@media (max-width: 768px) {
  .search-form {
    gap: 4px;
  }
  
  .search-form .el-form-item {
    flex: 0 0 auto;
  }
  
  .search-form .el-input {
    width: 100px !important;
  }
  
  .search-form .el-form-item__label {
    font-size: 11px;
    padding-right: 4px !important;
  }
  
  .search-form .el-input__inner {
    font-size: 11px;
    padding: 0 6px !important;
  }
}

@media (max-width: 480px) {
  .search-form {
    gap: 3px;
  }
  
  .search-form .el-input {
    width: 80px !important;
  }
  
  .search-form .el-form-item__label {
    font-size: 10px;
    padding-right: 3px !important;
  }
  
  .search-form .el-input__inner {
    font-size: 10px;
    padding: 0 4px !important;
  }
}

.no-margin {
  margin: 0 !important;
}

.operation-buttons {
  display: flex;
  flex-wrap: nowrap;
  gap: 6px;
  margin-left: auto !important;
  margin-right: 8px !important;
}

@media (max-width: 768px) {
  .operation-buttons {
    margin-left: auto !important;
    gap: 3px;
  }
  
  .operation-buttons .el-button {
    padding: 4px 6px;
    font-size: 11px;
  }
}

@media (max-width: 480px) {
  .operation-buttons {
    gap: 2px;
  }
  
  .operation-buttons .el-button {
    padding: 3px 5px;
    font-size: 10px;
  }
  
  .operation-buttons .el-button--small {
    padding: 3px 5px;
  }
}

.list-header {
  margin-bottom: 8px;
  font-size: 12px;
  color: #666;
}

.log-list {
  flex: 1;
  display: flex;
  flex-direction: column;
}

:deep(.el-table) {
  font-size: 12px;
}

:deep(.compact-table .el-table__row td) {
  padding: 4px 0;
}

:deep(.compact-table .el-table__header th) {
  padding: 6px 0;
}

:deep(.el-table .cell) {
  line-height: 20px;
  padding: 0 8px;
}

:deep(.el-table th) {
  background-color: #f5f7fa;
  color: #606266;
  font-weight: 500;
  font-size: 12px;
}

.success-status {
  color: #67c23a;
}

.error-status {
  color: #f56c6c;
  font-weight: 500;
}

.downstream-tag {
  color: #1890ff;
  font-weight: 500;
}

.text-muted {
  color: #c0c4cc;
}

.operation-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
}

:deep(.el-button--text) {
  color: #409EFF;
  font-size: 12px;
  padding: 0;
  height: auto;
  min-height: unset;
}

:deep(.el-divider--vertical) {
  height: 12px;
  margin: 0 2px;
}

:deep(.el-form-item__label) {
  font-size: 12px;
  color: #606266;
  padding-right: 12px !important;
}

:deep(.el-input__wrapper) {
  padding-left: 8px;
  padding-right: 8px;
}

:deep(.el-form--inline .el-form-item__content) {
  margin-right: 0;
}

:deep(.el-button--small) {
  padding: 5px 11px;
}

:deep(.el-button.is-circle) {
  margin-left: 8px;
}

:deep(.el-input-group__append) {
  padding: 0 8px;
}

:deep(.el-dialog__body) {
  padding: 20px;
}

:deep(.el-tooltip__trigger) {
  margin-left: 8px;
}

.detail-content {
  padding: 0 16px;
}

.trace-item {
  margin-bottom: 8px;
}

.trace-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  background: #fafafa;
  border-radius: 2px;
  cursor: pointer;
  user-select: none;
}

.trace-header:hover {
  background: #f0f0f0;
}

.expand-icon {
  width: 16px;
  height: 16px;
  line-height: 14px;
  text-align: center;
  border: 1px solid #d9d9d9;
  border-radius: 2px;
  font-size: 12px;
  color: #666;
}

.trace-type {
  padding: 2px 6px;
  border-radius: 2px;
  font-size: 12px;
  font-weight: 500;
}

.trace-type.HTTP {
  background-color: #e6f7ff;
  color: #1890ff;
}

.trace-type.RPC {
  background-color: #f6ffed;
  color: #52c41a;
}

.trace-type.SQL {
  background-color: #fff7e6;
  color: #fa8c16;
}

.trace-type.Redis {
  background-color: #fff1f0;
  color: #f5222d;
}

.trace-type.Process {
  background-color: #f9f0ff;
  color: #722ed1;
}

.trace-name {
  flex: 1;
  font-size: 13px;
  color: #333;
}

.trace-duration {
  font-size: 12px;
  color: #999;
  margin-left: 8px;
}

.trace-detail {
  margin: 4px 0 4px 32px;
  padding: 8px 12px;
  background: #fafafa;
  border-radius: 2px;
  font-size: 12px;
  font-family: monospace;
}

.trace-detail pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}

.trace-children {
  margin-top: 4px;
}

.http-detail-dialog :deep(.el-dialog__header) {
  border-bottom: 1px solid #ebeef5;
  margin-right: 0;
  padding: 16px 20px;
}

.http-detail-dialog :deep(.el-dialog__title) {
  font-size: 16px;
  font-weight: 600;
}

.section {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
  overflow: hidden;
}

.section-header {
  padding: 12px 16px;
  border-bottom: 1px solid #ebeef5;
  transition: background-color 0.3s;
}

.section-header.request {
  background: linear-gradient(to right, #e6f7ff, #f0f9ff);
}

.section-header.response {
  background: linear-gradient(to right, #f6ffed, #f9fff5);
}

.header-content {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-content .el-icon {
  font-size: 16px;
}

.section-header.request .el-icon {
  color: #1890ff;
}

.section-header.response .el-icon {
  color: #52c41a;
}

.title {
  font-size: 14px;
  font-weight: 600;
}

.section-header.request .title {
  color: #1890ff;
}

.section-header.response .title {
  color: #52c41a;
}

.title-with-action {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.section-content {
  padding: 16px;
  background-color: #fafafa;
  transition: background-color 0.3s;
}

.section-content:hover {
  background-color: #f5f5f5;
}

.json-content {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: "Monaco", "Menlo", "Ubuntu Mono", "Consolas", "source-code-pro", monospace;
  font-size: 13px;
  line-height: 1.6;
  color: #24292e;
}

.json-content ::selection {
  background: #b3d4fc;
}

.header-icon {
  margin-left: 4px;
  transition: transform 0.3s;
}

.header-icon.is-active {
  transform: rotate(180deg);
}

:deep(.el-button--primary.is-link) {
  color: #1890ff;
}

:deep(.el-button--primary.is-link:hover) {
  color: #40a9ff;
}

.trace-name :deep(.el-button--primary.is-link) {
  font-size: 13px;
  font-weight: normal;
}

.info-item {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
  font-size: 13px;
  line-height: 1.6;
}

.info-item:last-child {
  margin-bottom: 0;
}

.info-item .label {
  width: 80px;
  color: #666;
  flex-shrink: 0;
}

.info-item .value {
  color: #333;
}

.info-item .value.method {
  color: #1890ff;
  font-weight: 500;
}

.info-item .value.status {
  font-weight: 500;
}

.info-item .value.status.success {
  color: #52c41a;
}

.info-item .value.status.error {
  color: #f5222d;
}

.info-item .value.duration {
  color: #722ed1;
  font-weight: 500;
}

.info-item .value.source {
  color: #fa8c16;
  font-weight: 500;
}
</style> 