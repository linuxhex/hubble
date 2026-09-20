<template>
  <div class="service-load-page">
    <div class="page-header">
      <div>
        <h2>服务负载 · 扩容决策参考</h2>
        <div class="page-subtitle">基于历史峰值水位与增长趋势，评估各服务是否需要扩容</div>
      </div>
      <div class="header-actions">
        <el-button size="small" @click="collectNow" :loading="collecting" :disabled="collecting">立即采集</el-button>
        <el-button size="small" type="primary" @click="refresh" :loading="loading">刷新</el-button>
      </div>
    </div>

    <!-- 筛选区 -->
    <div class="filter-bar">
      <el-select v-model="timeRange" placeholder="时间范围" size="small" style="width: 130px" @change="onRangeChange">
        <el-option label="最近7天" value="7" />
        <el-option label="最近30天" value="30" />
        <el-option label="最近90天" value="90" />
        <el-option label="最近180天" value="180" />
      </el-select>
      <el-input v-model="nameFilter" placeholder="按服务名过滤" size="small" clearable style="width: 220px" />
      <div class="threshold-info">
        <span class="threshold-item"><span class="dot yellow"></span>CPU ≥80% / 内存 ≥85% 建议扩容</span>
        <span class="threshold-item"><span class="dot red"></span>CPU ≥90% / 内存 ≥95% 紧急扩容</span>
      </div>
      <div class="source-note">数据来源：Prometheus（CPU/内存）+ SLS（QPS），每日 00:00/12:00 定时采集</div>
    </div>

    <!-- 概览统计（点击卡片筛选对应分级） -->
    <div class="summary-cards" v-if="items.length > 0">
      <div class="summary-card urgent" :class="{ active: activeLevel === 'URGENT' }" @click="toggleLevel('URGENT')">
        <div class="summary-count">{{ summary.urgent || 0 }}</div>
        <div class="summary-label">紧急</div>
      </div>
      <div class="summary-card suggest" :class="{ active: activeLevel === 'SUGGEST' }" @click="toggleLevel('SUGGEST')">
        <div class="summary-count">{{ summary.suggest || 0 }}</div>
        <div class="summary-label">建议</div>
      </div>
      <div class="summary-card watch" :class="{ active: activeLevel === 'WATCH' }" @click="toggleLevel('WATCH')">
        <div class="summary-count">{{ summary.watch || 0 }}</div>
        <div class="summary-label">关注</div>
      </div>
      <div class="summary-card normal" :class="{ active: activeLevel === 'NORMAL' }" @click="toggleLevel('NORMAL')">
        <div class="summary-count">{{ summary.normal || 0 }}</div>
        <div class="summary-label">正常</div>
      </div>
      <div class="summary-card insufficient" :class="{ active: activeLevel === 'INSUFFICIENT' }" @click="toggleLevel('INSUFFICIENT')">
        <div class="summary-count">{{ summary.insufficient || 0 }}</div>
        <div class="summary-label">数据不足</div>
      </div>
      <div class="summary-meta">
        <template v-if="activeLevel">已筛选「{{ adviceLabel(activeLevel) }}」</template>
        共 {{ summary.total || 0 }} 个服务 · 评估窗口 {{ timeRange }} 天 · 更新于 {{ generatedAt }}
      </div>
    </div>

    <!-- 扩容决策总表 -->
    <div class="table-section" v-if="items.length > 0">
      <el-table
        :data="filteredItems"
        stripe
        border
        size="small"
        style="width: 100%"
        row-key="appName"
        @expand-change="handleExpand"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="drilldown">
              <div class="drilldown-loading" v-if="trendLoading[row.appName]">
                <el-skeleton :rows="3" animated />
              </div>
              <el-tabs v-else v-model="activeTab[row.appName]">
                <el-tab-pane label="CPU" name="cpu">
                  <ServiceLoadTrendChart :records="trendCache[row.appName] || []" metric="cpu" />
                </el-tab-pane>
                <el-tab-pane label="内存" name="memory">
                  <ServiceLoadTrendChart :records="trendCache[row.appName] || []" metric="memory" />
                </el-tab-pane>
                <el-tab-pane label="QPS" name="qps">
                  <ServiceLoadTrendChart :records="trendCache[row.appName] || []" metric="qps" />
                </el-tab-pane>
              </el-tabs>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="appName" label="服务" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="app-name">{{ row.appName }}</span>
            <el-tag v-if="row.partialToday === 1" size="small" type="warning" effect="plain" style="margin-left: 6px">今日半天</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="CPU 峰值" width="150">
          <template #default="{ row }">
            <div class="metric-cell" v-if="row.cpuPeak != null">
              <span :class="waterTextClass(row.cpuWaterLevel)">{{ row.cpuPeak }}%</span>
              <el-progress :percentage="barPct(row.cpuPeak)" :color="waterColor(row.cpuWaterLevel)" :stroke-width="6" :show-text="false" />
            </div>
            <span v-else class="no-data">—</span>
          </template>
        </el-table-column>
        <el-table-column label="内存峰值" width="150">
          <template #default="{ row }">
            <div class="metric-cell" v-if="row.memPeak != null">
              <span :class="waterTextClass(row.memWaterLevel)">{{ row.memPeak }}%</span>
              <el-progress :percentage="barPct(row.memPeak)" :color="waterColor(row.memWaterLevel)" :stroke-width="6" :show-text="false" />
            </div>
            <span v-else class="no-data">—</span>
          </template>
        </el-table-column>
        <el-table-column label="QPS 峰值" width="100" align="right">
          <template #default="{ row }">
            <span v-if="row.qpsPeak != null">{{ row.qpsPeak }}</span>
            <span v-else class="no-data">—</span>
          </template>
        </el-table-column>
        <el-table-column label="环比（近7天）" width="130">
          <template #default="{ row }">
            <div class="growth-cell">
              <div>
                <span class="growth-key">CPU</span>
                <span :class="growthClass(row.cpuGrowthPct)">{{ growthText(row.cpuGrowthPct) }}</span>
              </div>
              <div>
                <span class="growth-key">内存</span>
                <span :class="growthClass(row.memGrowthPct)">{{ growthText(row.memGrowthPct) }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="预测触顶" min-width="150">
          <template #default="{ row }">
            <div class="predict-cell">
              <span>{{ row.prediction || '—' }}</span>
              <el-tag v-if="row.confidence && row.confidence !== 'LOW'" size="small" :type="confidenceType(row.confidence)" effect="plain">
                {{ confidenceLabel(row.confidence) }}置信
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="扩容建议" width="110" align="center">
          <template #default="{ row }">
            <el-tooltip :content="row.adviceText" placement="top" :disabled="!row.adviceText">
              <el-tag size="small" :type="adviceType(row.adviceLevel)">{{ adviceLabel(row.adviceLevel) }}</el-tag>
            </el-tooltip>
            <div v-if="row.adviceTargets" class="advice-target">{{ row.adviceTargets }}</div>
          </template>
        </el-table-column>
        <el-table-column label="数据完整度" width="110">
          <template #default="{ row }">
            <div class="completeness-cell">
              <span :class="row.completeness < 60 ? 'metric-warning' : 'metric-normal'">{{ row.completeness }}%</span>
              <span class="completeness-detail">{{ row.sampleCount }}/{{ row.coverageDays }}天</span>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-tip">点击行首箭头展开单服务趋势（CPU / 内存 / QPS），含阈值线与半天数据标记</div>
    </div>

    <!-- 空状态 -->
    <div v-if="!loading && items.length === 0" class="empty-state">
      <el-empty description="暂无服务负载数据">
        <div class="empty-guide">
          <p>服务负载数据每日 00:00 / 12:00 自动采集，依赖内网数据源（Prometheus / SLS）。</p>
          <p>当前可手动触发采集（需内网可达）。</p>
          <div class="empty-actions">
            <el-button type="primary" size="small" @click="collectNow" :loading="collecting">立即采集</el-button>
          </div>
        </div>
      </el-empty>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getAssessment, getServiceLoadTrend, manualCollectServiceLoad } from '@/api/service-load.js'
import ServiceLoadTrendChart from './ServiceLoadTrendChart.vue'

const timeRange = ref('30')
const nameFilter = ref('')
const activeLevel = ref('')
const loading = ref(false)
const collecting = ref(false)

const items = ref([])
const summary = ref({})
const generatedAt = ref('')

const trendCache = reactive({})
const trendLoading = reactive({})
const activeTab = reactive({})

const LEVEL_ORDER = { URGENT: 0, SUGGEST: 1, WATCH: 2, NORMAL: 3, INSUFFICIENT: 4 }

const sortedItems = computed(() => {
  return [...items.value].sort((a, b) =>
    (LEVEL_ORDER[a.adviceLevel] ?? 9) - (LEVEL_ORDER[b.adviceLevel] ?? 9) || a.appName.localeCompare(b.appName))
})

const toggleLevel = (level) => {
  activeLevel.value = activeLevel.value === level ? '' : level
}

const filteredItems = computed(() => {
  let list = sortedItems.value
  if (activeLevel.value) {
    list = list.filter(i => i.adviceLevel === activeLevel.value)
  }
  const kw = nameFilter.value.trim().toLowerCase()
  if (kw) {
    list = list.filter(i => i.appName.toLowerCase().includes(kw))
  }
  return list
})

const formatDate = (d) => {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

const startDateStr = () => {
  const d = new Date()
  d.setDate(d.getDate() - parseInt(timeRange.value))
  return formatDate(d)
}

const fetchAssessment = async () => {
  loading.value = true
  try {
    const res = await getAssessment(parseInt(timeRange.value))
    if (res.code === 200 && res.data) {
      items.value = res.data.items || []
      summary.value = res.data.summary || {}
      generatedAt.value = (res.data.generatedAt || '').replace('T', ' ').substring(0, 16)
    }
  } catch (e) {
    console.error('获取扩容评估失败:', e)
  } finally {
    loading.value = false
  }
}

const refresh = () => fetchAssessment()

const onRangeChange = () => {
  Object.keys(trendCache).forEach(k => delete trendCache[k])
  fetchAssessment()
}

const handleExpand = async (row, expandedRows) => {
  if (!expandedRows.includes(row)) return
  if (!activeTab[row.appName]) activeTab[row.appName] = 'cpu'
  if (trendCache[row.appName]) return
  trendLoading[row.appName] = true
  try {
    const res = await getServiceLoadTrend(row.appName, startDateStr())
    if (res.code === 200 && res.data) {
      trendCache[row.appName] = res.data
    }
  } catch (e) {
    console.error('获取趋势数据失败:', e)
  } finally {
    trendLoading[row.appName] = false
  }
}

const collectNow = async () => {
  collecting.value = true
  try {
    const res = await manualCollectServiceLoad(formatDate(new Date()))
    if (res.code === 200) {
      ElMessage.success('采集任务完成')
      await fetchAssessment()
    } else {
      ElMessage.error(res.message || '采集失败')
    }
  } catch (e) {
    ElMessage.error('采集失败：' + (e.message || '请检查内网数据源可达性'))
  } finally {
    collecting.value = false
  }
}

const waterColor = (level) => {
  if (level === 'red') return '#f56c6c'
  if (level === 'yellow') return '#e6a23c'
  return '#67c23a'
}

const waterTextClass = (level) => {
  if (level === 'red') return 'metric-critical'
  if (level === 'yellow') return 'metric-warning'
  return 'metric-normal'
}

const barPct = (val) => {
  const n = Number(val)
  if (isNaN(n)) return 0
  return Math.max(0, Math.min(100, n))
}

const growthText = (g) => (g === null || g === undefined) ? '—' : `${g >= 0 ? '+' : ''}${g}%`

const growthClass = (g) => {
  if (g === null || g === undefined) return 'no-data'
  if (g >= 30) return 'metric-critical'
  if (g >= 15) return 'metric-warning'
  return 'metric-normal'
}

const confidenceLabel = (c) => ({ HIGH: '高', MEDIUM: '中', LOW: '低' }[c] || c)
const confidenceType = (c) => ({ HIGH: 'success', MEDIUM: 'warning', LOW: 'info' }[c] || 'info')

const adviceLabel = (level) => ({
  URGENT: '紧急扩容', SUGGEST: '建议扩容', WATCH: '关注', NORMAL: '正常', INSUFFICIENT: '数据不足'
}[level] || level)

const adviceType = (level) => ({
  URGENT: 'danger', SUGGEST: 'warning', WATCH: 'primary', NORMAL: 'success', INSUFFICIENT: 'info'
}[level] || 'info')

onMounted(fetchAssessment)
</script>

<style scoped>
.service-load-page {
  padding: 16px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  background: #f5f7fa;
  overflow-y: auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: white;
  padding: 12px 20px;
  border-radius: 4px;
}

.page-header h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
  color: #303133;
}

.page-subtitle {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.filter-bar {
  background: white;
  padding: 12px 20px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.threshold-info {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #606266;
}

.threshold-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.source-note {
  margin-left: auto;
  font-size: 12px;
  color: #c0c4cc;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot.yellow { background: #e6a23c; }
.dot.red { background: #f56c6c; }

.summary-cards {
  display: flex;
  gap: 12px;
  align-items: stretch;
}

.summary-card {
  flex: 0 0 110px;
  background: white;
  border-radius: 4px;
  padding: 12px 16px;
  text-align: center;
  border-top: 3px solid var(--card-color, #dcdfe6);
  cursor: pointer;
  user-select: none;
  transition: transform 0.15s, box-shadow 0.15s;
}

.summary-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.summary-card.active {
  box-shadow: 0 0 0 2px var(--card-color, #dcdfe6);
}

.summary-card.urgent { --card-color: #f56c6c; }
.summary-card.suggest { --card-color: #e6a23c; }
.summary-card.watch { --card-color: #409eff; }
.summary-card.normal { --card-color: #67c23a; }
.summary-card.insufficient { --card-color: #909399; }

.summary-card .summary-count { color: var(--card-color, #909399); }

.summary-count {
  font-size: 24px;
  font-weight: 600;
  line-height: 1.2;
}

.summary-label {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.summary-meta {
  margin-left: auto;
  align-self: center;
  font-size: 12px;
  color: #c0c4cc;
}

.table-section {
  background: white;
  border-radius: 4px;
  padding: 16px;
}

.table-tip {
  font-size: 12px;
  color: #c0c4cc;
  margin-top: 8px;
}

.drilldown {
  padding: 8px 16px 16px;
  background: #fafbfc;
}

.drilldown-loading {
  padding: 16px;
}

.app-name {
  font-weight: 500;
}

.metric-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.metric-cell .el-progress {
  width: 90%;
}

.growth-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}

.growth-key {
  display: inline-block;
  width: 32px;
  color: #909399;
}

.predict-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.completeness-cell {
  display: flex;
  flex-direction: column;
  font-size: 12px;
}

.completeness-detail {
  color: #909399;
}

.advice-target {
  margin-top: 2px;
  font-size: 11px;
  color: #909399;
}

.no-data {
  color: #c0c4cc;
}

.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: white;
  border-radius: 4px;
  min-height: 300px;
}

.empty-guide {
  font-size: 13px;
  color: #606266;
  line-height: 1.8;
}

.empty-guide p {
  margin: 0;
}

.empty-actions {
  margin-top: 12px;
}

.metric-critical {
  color: #f56c6c;
  font-weight: bold;
}

.metric-warning {
  color: #e6a23c;
  font-weight: 500;
}

.metric-normal {
  color: #67c23a;
}

:deep(.el-table) {
  font-size: 12px;
}

:deep(.el-table th) {
  background: #f5f7fa;
  color: #606266;
  font-weight: 500;
}

:deep(.el-table .el-table__expanded-cell) {
  padding: 0;
}
</style>
