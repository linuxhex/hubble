<template>
  <div class="user-behavior-trace-query">
    <!-- 搜索条件区域 -->
    <div class="search-area">
      <el-form :model="queryForm" inline class="search-form">
        <el-form-item label="查询关键字" class="no-margin" required>
          <el-input
            v-model="queryForm.keyword"
            placeholder="请输入查询关键字（用户ID/手机号等）"
            clearable
            size="small"
            style="width: 200px"
          />
        </el-form-item>

        <el-form-item label="查询日期" class="no-margin" required>
          <el-date-picker
            v-model="selectedDate"
            type="date"
            placeholder="选择日期"
            value-format="YYYY-MM-DD"
            size="small"
            style="width: 160px"
            :default-value="new Date()"
          />
        </el-form-item>

        <el-form-item label="每页数量" class="no-margin">
          <el-input-number
            v-model="queryForm.limit"
            :min="10"
            :max="500"
            size="small"
            style="width: 120px"
          />
        </el-form-item>

        <el-form-item class="no-margin operation-buttons">
          <el-button type="primary" size="small" @click="handleQuery" :loading="querying">
            查询
          </el-button>
          <el-button size="small" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 查询结果 -->
    <div v-if="hasResults || loadingMore" class="results-area">
      <div class="list-header">
        <span>查询结果（已加载 {{ resultData.items.length }} 条{{ resultData.hasMore ? '，滚动加载更多...' : '' }}）</span>
      </div>
      <div 
        ref="scrollContainer"
        class="scroll-container"
        @scroll="handleScroll"
      >
        <UserBehaviorTimeline 
          :key="queryKey" 
          :items="resultData.items"
          :time-range="currentTimeRange"
        />
        <div v-if="loadingMore" class="loading-more">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>加载中...</span>
        </div>
        <div v-if="!resultData.hasMore && resultData.items.length > 0" class="no-more">
          <span>已加载全部数据</span>
        </div>
      </div>
    </div>

    <!-- 空结果提示 -->
    <div v-if="!querying && hasQueried && !hasResults" class="empty-area">
      <el-empty description="未查询到符合条件的操作轨迹" />
    </div>

    <!-- 查询中提示 -->
    <div v-if="querying" class="querying-area">
      <el-result icon="loading" title="查询中..." sub-title="请稍候" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { queryUserBehaviorTrace } from '@/api/user-behavior-trace-query'
import UserBehaviorTimeline from '@/components/UserBehaviorTimeline.vue'

// 获取当天日期（yyyy-MM-dd格式）
const getTodayDate = () => {
  const today = new Date()
  const year = today.getFullYear()
  const month = String(today.getMonth() + 1).padStart(2, '0')
  const day = String(today.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

// 查询表单
const queryForm = ref({
  keyword: '',
  limit: 100
})

// 选中的日期（默认今天）
const selectedDate = ref(getTodayDate())

// 查询状态
const querying = ref(false)
const loadingMore = ref(false)
const hasQueried = ref(false)
const resultData = ref({
  items: [],
  total: 0,
  hasMore: false
})

// 滚动容器引用
const scrollContainer = ref(null)

// 当前查询参数（用于加载更多）
const currentQueryParams = ref(null)

// 查询key（用于强制刷新组件）
const queryKey = ref(0)

// 当前查询的时间范围（Unix时间戳，秒）
const currentTimeRange = ref(null)

// 计算属性
const hasResults = computed(() => resultData.value.items.length > 0)

// 执行查询
const handleQuery = async (isLoadMore = false) => {
  // 验证表单
  if (!queryForm.value.keyword || queryForm.value.keyword.trim() === '') {
    ElMessage.warning('请输入查询关键字')
    return
  }

  if (!selectedDate.value) {
    ElMessage.warning('请选择查询日期')
    return
  }

  // 检查是否是新的查询（关键字或日期变化）
  const currentKeyword = queryForm.value.keyword.trim()
  const currentDate = selectedDate.value
  const isNewQuery = !currentQueryParams.value || 
                     currentQueryParams.value.keyword !== currentKeyword ||
                     currentQueryParams.value.date !== currentDate

  // 计算时间范围（从日期计算开始和结束时间，Unix时间戳，秒）
  const dateObj = new Date(currentDate + ' 00:00:00')
  const startTime = Math.floor(dateObj.getTime() / 1000)
  const endTime = startTime + 86400 - 1 // 当天23:59:59
  currentTimeRange.value = { startTime, endTime }

  if (isLoadMore && !isNewQuery) {
    // 加载更多：只有在相同查询条件下才追加数据
    loadingMore.value = true
  } else {
    // 首次查询或新查询：重置数据
    querying.value = true
    hasQueried.value = true
    resultData.value = { items: [], total: 0, hasMore: false }
    currentQueryParams.value = {
      keyword: currentKeyword,
      date: currentDate,
      limit: queryForm.value.limit || 100
    }
    // 更新key强制刷新组件
    queryKey.value++
    // 重置滚动位置
    if (scrollContainer.value) {
      scrollContainer.value.scrollTop = 0
    }
  }

  try {
    const request = {
      keyword: queryForm.value.keyword.trim(),
      date: selectedDate.value,
      offset: isLoadMore ? resultData.value.items.length : 0,
      limit: queryForm.value.limit || 100
    }

    const res = await queryUserBehaviorTrace(request)
    if (res.code === 200 && res.data) {
      if (isLoadMore) {
        // 加载更多：追加数据
        resultData.value.items.push(...res.data.items)
        resultData.value.hasMore = res.data.hasMore || false
      } else {
        // 首次查询：替换数据
        resultData.value = res.data
        if (resultData.value.items.length === 0) {
          ElMessage.info('未查询到符合条件的操作轨迹')
        } else {
          ElMessage.success(`查询成功，已加载 ${resultData.value.items.length} 条记录`)
        }
      }
    } else {
      ElMessage.error(res.message || '查询失败')
    }
  } catch (error) {
    console.error('查询失败:', error)
    ElMessage.error('查询失败: ' + (error.message || '未知错误'))
  } finally {
    querying.value = false
    loadingMore.value = false
  }
}

// 滚动事件处理
const handleScroll = () => {
  if (!scrollContainer.value || loadingMore.value || !resultData.value.hasMore) {
    return
  }

  const container = scrollContainer.value
  const scrollTop = container.scrollTop
  const scrollHeight = container.scrollHeight
  const clientHeight = container.clientHeight

  // 当滚动到距离底部100px时，触发加载更多
  if (scrollTop + clientHeight >= scrollHeight - 100) {
    handleQuery(true)
  }
}

// 重置表单
const handleReset = () => {
  queryForm.value = {
    keyword: '',
    limit: 100
  }
  selectedDate.value = getTodayDate()
  resultData.value = { items: [], total: 0, hasMore: false }
  hasQueried.value = false
  currentQueryParams.value = null
  queryKey.value++
  // 重置滚动位置
  if (scrollContainer.value) {
    scrollContainer.value.scrollTop = 0
  }
}

// 组件挂载
onMounted(() => {
  // 默认选择今天
  selectedDate.value = getTodayDate()
})
</script>

<style scoped>
.user-behavior-trace-query {
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
  gap: 16px;
}

.no-margin {
  margin: 0 !important;
}

.operation-buttons {
  margin-left: auto !important;
  white-space: nowrap;
  margin-right: 8px !important;
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
  font-size: 12px;
  color: #666;
  font-weight: 500;
}

.scroll-container {
  flex: 1;
  overflow-y: auto;
  padding: 10px 0;
}

.loading-more {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  color: #909399;
  gap: 8px;
  font-size: 12px;
}

.no-more {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
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

.querying-area {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: white;
  border-radius: 4px;
  min-height: 300px;
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
  font-size: 12px;
}
</style>


