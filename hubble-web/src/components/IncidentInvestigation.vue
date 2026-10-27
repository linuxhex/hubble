<template>
  <div class="incident-investigation">
    <!-- 搜索条件区域 -->
    <div class="search-area">
      <el-form :model="searchForm" inline class="search-form">
        <el-form-item label="告警来源" class="no-margin">
          <el-select v-model="searchForm.source" placeholder="全部来源" clearable size="small" style="width: 160px" @change="loadList">
            <el-option label="ARMS" value="ARMS" />
            <el-option label="依赖RT" value="DEP_RT" />
            <el-option label="依赖错误率" value="DEP_ERR" />
            <el-option label="中间件" value="MW" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态" class="no-margin">
          <el-select v-model="searchForm.status" placeholder="全部状态" clearable size="small" style="width: 160px" @change="loadList">
            <el-option label="调查中" value="INVESTIGATING" />
            <el-option label="已完成" value="DONE" />
            <el-option label="失败" value="FAILED" />
          </el-select>
        </el-form-item>
        <el-form-item class="no-margin operation-buttons">
          <el-button type="primary" size="small" @click="loadList">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 列表 -->
    <div class="table-area">
      <el-table :data="list" v-loading="loading" size="small" border stripe @row-click="openDetail">
        <el-table-column prop="createdAt" label="时间" width="160" />
        <el-table-column prop="alertSource" label="来源" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="sourceTagType(row.alertSource)">{{ sourceLabel(row.alertSource) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="alertName" label="告警" min-width="160" show-overflow-tooltip />
        <el-table-column prop="serviceName" label="服务/实例" min-width="140" show-overflow-tooltip />
        <el-table-column prop="severity" label="级别" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" type="danger">{{ row.severity || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rootCause" label="根因摘要" min-width="240" show-overflow-tooltip />
        <el-table-column prop="confidence" label="置信度" width="90" align="center">
          <template #default="{ row }">
            {{ row.confidence != null ? Math.round(row.confidence * 100) + '%' : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'FAILED'" size="small" type="warning" link @click.stop="handleRetry(row)">重试</el-button>
            <el-button size="small" type="primary" link @click.stop="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-area">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          size="small"
          @current-change="loadList"
          @size-change="loadList"
        />
      </div>
    </div>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawerVisible" :title="detail?.alertName || '调查详情'" size="55%">
      <div v-if="detail" class="detail-body" v-loading="detailLoading">
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="告警来源">{{ sourceLabel(detail.alertSource) }}</el-descriptions-item>
          <el-descriptions-item label="告警类型">{{ detail.alertType }}</el-descriptions-item>
          <el-descriptions-item label="服务/实例">{{ detail.serviceName }}</el-descriptions-item>
          <el-descriptions-item label="级别">{{ detail.severity }}</el-descriptions-item>
          <el-descriptions-item label="当前值" :span="2">{{ detail.alertValue || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="statusTagType(detail.status)">{{ statusLabel(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="置信度">
            <el-progress v-if="detail.confidence != null" :percentage="Math.round(detail.confidence * 100)" :stroke-width="10" style="width: 160px" />
            <span v-else>-</span>
          </el-descriptions-item>
        </el-descriptions>

        <template v-if="detail.status === 'DONE'">
          <h4>根因</h4>
          <div class="report-block root-cause">{{ detail.rootCause }}</div>
          <h4>分析</h4>
          <div class="report-block">{{ detail.analysisSummary }}</div>
          <h4>处置建议</h4>
          <div class="report-block recommendation">{{ detail.recommendation }}</div>

          <h4>取证证据</h4>
          <el-collapse>
            <el-collapse-item
              v-for="(ev, i) in evidences"
              :key="i"
              :name="i"
            >
              <template #title>
                <span class="ev-title">
                  {{ ev.name }}
                  <el-tag v-if="ev.degraded" size="small" type="info">降级</el-tag>
                </span>
              </template>
              <div v-if="ev.degraded" class="ev-note">{{ ev.note }}</div>
              <pre v-else class="ev-content">{{ ev.content }}</pre>
            </el-collapse-item>
          </el-collapse>
        </template>

        <template v-else-if="detail.status === 'FAILED'">
          <h4>失败原因</h4>
          <div class="report-block failed">{{ detail.errorMessage }}</div>
          <el-button type="warning" size="small" @click="handleRetry(detail)">
            <el-icon><Refresh /></el-icon>
            重试调查
          </el-button>
        </template>

        <template v-else>
          <el-alert title="调查进行中，请稍后刷新查看报告" type="info" :closable="false" />
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { getIncidentList, getIncidentDetail, retryIncident } from '@/api/incident'

const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const searchForm = reactive({ source: '', status: '' })

const drawerVisible = ref(false)
const detail = ref(null)
const detailLoading = ref(false)
const evidences = ref([])

const SOURCE_MAP = { ARMS: 'ARMS', DEP_RT: '依赖RT', DEP_ERR: '依赖错误率', MW: '中间件' }
const sourceLabel = (s) => SOURCE_MAP[s] || s || '-'
const sourceTagType = (s) => ({ ARMS: 'danger', DEP_RT: 'warning', DEP_ERR: 'danger', MW: 'info' }[s] || 'info')

const statusLabel = (s) => ({ INVESTIGATING: '调查中', DONE: '已完成', FAILED: '失败' }[s] || s)
const statusTagType = (s) => ({ INVESTIGATING: 'warning', DONE: 'success', FAILED: 'danger' }[s] || 'info')

const loadList = async () => {
  loading.value = true
  try {
    const { data } = await getIncidentList({
      page: page.value,
      size: size.value,
      source: searchForm.source || undefined,
      status: searchForm.status || undefined
    })
    list.value = data?.data?.records || []
    total.value = data?.data?.total || 0
  } catch (e) {
    console.error('加载事故调查列表失败', e)
  } finally {
    loading.value = false
  }
}

const openDetail = async (row) => {
  drawerVisible.value = true
  detailLoading.value = true
  try {
    const { data } = await getIncidentDetail(row.id)
    detail.value = data?.data
    try {
      evidences.value = detail.value?.evidenceJson ? JSON.parse(detail.value.evidenceJson) : []
    } catch {
      evidences.value = []
    }
  } finally {
    detailLoading.value = false
  }
}

const handleRetry = async (row) => {
  try {
    await retryIncident(row.id)
    ElMessage.success('已重新提交调查')
    drawerVisible.value = false
    loadList()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '重试失败')
  }
}

onMounted(loadList)
</script>

<style scoped>
.incident-investigation {
  padding: 12px;
  height: 100%;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
}
.search-area {
  margin-bottom: 8px;
}
.search-form :deep(.el-form-item) {
  margin-bottom: 8px;
}
.table-area {
  flex: 1;
  min-height: 0;
}
.pagination-area {
  display: flex;
  justify-content: flex-end;
  padding: 8px 0;
}
.detail-body h4 {
  margin: 16px 0 8px;
}
.report-block {
  background: var(--el-fill-color-light);
  border-radius: 4px;
  padding: 10px 12px;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
  font-size: 13px;
}
.root-cause {
  border-left: 3px solid var(--el-color-danger);
}
.recommendation {
  border-left: 3px solid var(--el-color-success);
}
.failed {
  border-left: 3px solid var(--el-color-warning);
  margin-bottom: 12px;
}
.ev-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.ev-note {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.ev-content {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 320px;
  overflow: auto;
}
:deep(.el-table__row) {
  cursor: pointer;
}
</style>
