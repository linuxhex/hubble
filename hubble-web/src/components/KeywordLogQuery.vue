<template>
  <div class="keyword-log-query">
    <!-- 搜索条件区域 -->
    <div class="search-area">
      <el-form :model="queryForm" label-width="100px" class="search-form">
        <el-row :gutter="16">
          <el-col :span="24">
            <el-form-item label="查询关键字" required>
              <el-input
                v-model="queryForm.keyword"
                placeholder="请输入查询关键字"
                clearable
                size="small"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="24">
            <el-form-item label="时间范围" required>
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
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="Logstore">
              <el-input
                v-model="queryForm.logstore"
                placeholder="默认：all"
                clearable
                size="small"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>

          <el-col :span="8">
            <el-form-item label="排序方式">
              <el-select v-model="queryForm.sortOrder" size="small" style="width: 100%">
                <el-option label="倒序（最新在前）" value="desc" />
                <el-option label="正序（最早在前）" value="asc" />
              </el-select>
            </el-form-item>
          </el-col>

          <el-col :span="8">
            <el-form-item label="日志级别">
              <el-select
                v-model="levelFilter"
                multiple
                collapse-tags
                clearable
                placeholder="全部级别"
                size="small"
                style="width: 100%"
              >
                <el-option label="INFO" value="INFO" />
                <el-option label="WARN" value="WARN" />
                <el-option label="ERROR" value="ERROR" />
                <el-option label="DEBUG" value="DEBUG" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item>
          <el-button type="primary" size="small" @click="handleQuery" :loading="querying">
            查询
          </el-button>
          <el-button size="small" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 查询结果 -->
    <div v-if="hasResults || querying" class="results-area">
      <div class="list-header">
        <span>查询结果</span>
        <span v-if="levelFilter.length > 0" class="filter-hint">
          已按级别筛选：{{ filteredLogs.length }} / {{ allLogs.length }} 条
        </span>
      </div>
      <div v-if="querying && allLogs.length === 0" class="loading-container">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>查询中...</span>
      </div>

      <div v-else-if="hasResults">
        <LogViewer
          :logs="filteredLogs"
          :start-time="currentTimeRange?.from"
          :end-time="currentTimeRange?.to"
          :logstore="queryForm.logstore || 'all'"
          :highlight="queryForm.keyword"
        />

        <!-- 加载更多按钮 -->
        <div v-if="resultData.hasMore" class="load-more-container">
          <el-button size="small" @click="handleLoadMore" :loading="loadingMore">
            加载更多
          </el-button>
        </div>
        <div v-else-if="allLogs.length > 0" class="no-more-hint">
          已加载全部数据
        </div>
      </div>
    </div>

    <!-- 初始引导 -->
    <div v-if="!querying && !hasQueried && !hasResults" class="empty-area">
      <el-empty description="输入关键字开始日志检索">
        <div class="empty-guide">
          <p>使用技巧：</p>
          <p>1. 关键字支持接口路径、异常类名、订单号、TraceId 等任意日志片段</p>
          <p>2. Logstore 留空默认检索全部应用，也可指定单个日志库</p>
          <p>3. 时间范围默认今天，可用快捷区间一键切换</p>
        </div>
      </el-empty>
    </div>

    <!-- 空结果提示 -->
    <div v-if="!querying && hasQueried && !hasResults" class="empty-area">
      <el-empty description="未查询到符合条件的日志" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { queryKeywordLogs } from '@/api/keyword-log-query'
import LogViewer from '@/components/LogViewer.vue'

// 获取今天的时间范围（格式：YYYY-MM-DD HH:mm:ss）
const getTodayTimeRange = () => {
  const today = new Date()
  const year = today.getFullYear()
  const month = String(today.getMonth() + 1).padStart(2, '0')
  const day = String(today.getDate()).padStart(2, '0')
  
  const startTime = `${year}-${month}-${day} 00:00:00`
  const endTime = `${year}-${month}-${day} 23:59:59`
  
  return [startTime, endTime]
}

// 格式化日期时间为字符串
const formatDateTime = (date) => {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const seconds = String(date.getSeconds()).padStart(2, '0')
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
}

// 查询表单
const queryForm = ref({
  keyword: '',
  logstore: 'all',
  sortOrder: 'desc' // 默认倒序
})

// 时间范围（用于日期选择器），默认今天
const timeRange = ref(getTodayTimeRange())

// 时间范围快捷选项
const timeRangeShortcuts = [
  {
    text: '近6小时',
    value: () => {
      const end = new Date()
      const start = new Date()
      start.setTime(start.getTime() - 6 * 3600 * 1000)
      return [formatDateTime(start), formatDateTime(end)]
    }
  },
  {
    text: '近1天',
    value: () => {
      const end = new Date()
      const start = new Date()
      start.setTime(start.getTime() - 24 * 3600 * 1000)
      return [formatDateTime(start), formatDateTime(end)]
    }
  },
  {
    text: '近5天',
    value: () => {
      const end = new Date()
      const start = new Date()
      start.setTime(start.getTime() - 5 * 24 * 3600 * 1000)
      return [formatDateTime(start), formatDateTime(end)]
    }
  }
]

// 当前查询的时间范围（Unix时间戳，秒）
const currentTimeRange = ref(null)

// 查询状态
const querying = ref(false)
const loadingMore = ref(false)
const hasQueried = ref(false)
const currentOffset = ref(0)
const limit = 20 // 每次加载20条

// 所有已加载的日志
const allLogs = ref([])

// 日志级别筛选（前端过滤，多选）
const levelFilter = ref([])

const filteredLogs = computed(() => {
  if (!levelFilter.value || levelFilter.value.length === 0) return allLogs.value
  const levels = new Set(levelFilter.value.map((s) => s.toUpperCase()))
  return allLogs.value.filter((l) => levels.has((l.level || 'INFO').toUpperCase()))
})

const resultData = ref({
  logs: [],
  total: 0,
  hasMore: false
})

// 计算属性
const hasResults = computed(() => allLogs.value.length > 0)

// 执行查询（首次查询）
const handleQuery = async () => {
  // 验证表单
  if (!queryForm.value.keyword || queryForm.value.keyword.trim() === '') {
    ElMessage.warning('请输入查询关键字')
    return
  }

  if (!timeRange.value || timeRange.value.length !== 2) {
    ElMessage.warning('请选择时间范围')
    return
  }

  // 计算时间范围（Unix时间戳，秒）
  const startTime = Math.floor(new Date(timeRange.value[0]).getTime() / 1000)
  const endTime = Math.floor(new Date(timeRange.value[1]).getTime() / 1000)
  currentTimeRange.value = { from: startTime, to: endTime }

  // 重置状态
  currentOffset.value = 0
  allLogs.value = []
  querying.value = true
  hasQueried.value = true

  try {
    const request = {
      keyword: queryForm.value.keyword.trim(),
      logstore: queryForm.value.logstore || 'all',
      timeRange: {
        from: startTime,
        to: endTime
      },
      offset: 0,
      limit: limit,
      sortOrder: queryForm.value.sortOrder
    }

    const res = await queryKeywordLogs(request)
    if (res.code === 200 && res.data) {
      resultData.value = res.data
      allLogs.value = res.data.logs
      currentOffset.value = res.data.logs.length
      
      if (resultData.value.logs.length === 0) {
        ElMessage.info('未查询到符合条件的日志')
      } else {
        ElMessage.success(`查询成功，共 ${resultData.value.total} 条记录`)
      }
    } else {
      ElMessage.error(res.message || '查询失败')
    }
  } catch (error) {
    console.error('查询失败:', error)
    ElMessage.error('查询失败: ' + (error.message || '未知错误'))
  } finally {
    querying.value = false
  }
}

// 加载更多
const handleLoadMore = async () => {
  if (!currentTimeRange.value) return

  loadingMore.value = true

  try {
    const request = {
      keyword: queryForm.value.keyword.trim(),
      logstore: queryForm.value.logstore || 'all',
      timeRange: {
        from: currentTimeRange.value.from,
        to: currentTimeRange.value.to
      },
      offset: currentOffset.value,
      limit: limit,
      sortOrder: queryForm.value.sortOrder
    }

    const res = await queryKeywordLogs(request)
    if (res.code === 200 && res.data) {
      // 追加新数据
      allLogs.value.push(...res.data.logs)
      currentOffset.value += res.data.logs.length
      resultData.value.hasMore = res.data.hasMore
      
      ElMessage.success(`加载了 ${res.data.logs.length} 条记录`)
    } else {
      ElMessage.error(res.message || '加载失败')
    }
  } catch (error) {
    console.error('加载更多失败:', error)
    ElMessage.error('加载更多失败: ' + (error.message || '未知错误'))
  } finally {
    loadingMore.value = false
  }
}

// 重置表单
const handleReset = () => {
  queryForm.value = {
    keyword: '',
    logstore: 'all',
    sortOrder: 'desc'
  }
  levelFilter.value = []
  timeRange.value = getTodayTimeRange() // 重置为今天
  allLogs.value = []
  resultData.value = {
    logs: [],
    total: 0,
    hasMore: false
  }
  currentOffset.value = 0
  hasQueried.value = false
  currentTimeRange.value = null
}

// 组件挂载时初始化
onMounted(() => {
  // 默认选择今天
  timeRange.value = getTodayTimeRange()
})
</script>

<style scoped>
.keyword-log-query {
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

.search-form {
  max-width: 900px;
}

.results-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: white;
  border-radius: 4px;
  padding: 16px;
  overflow: hidden;
}

.list-header {
  margin-bottom: 12px;
  font-size: 14px;
  font-weight: 500;
  color: #333;
  display: flex;
  align-items: center;
  gap: 12px;
}

.filter-hint {
  font-size: 12px;
  font-weight: 400;
  color: #909399;
}

.loading-container {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px;
  color: #909399;
  gap: 12px;
  font-size: 14px;
}

.loading-container .el-icon {
  color: #409eff;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.load-more-container {
  margin-top: 16px;
  display: flex;
  justify-content: center;
}

.no-more-hint {
  margin-top: 16px;
  text-align: center;
  color: #909399;
  font-size: 12px;
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
}

:deep(.el-button--small) {
  padding: 5px 11px;
  font-size: 12px;
}
</style>
