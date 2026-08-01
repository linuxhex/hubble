<template>
  <div class="alert-config-management">
    <el-card body-style="padding: 16px">
      <!-- 搜索栏 -->
      <el-form :inline="true" :model="searchForm" class="search-form">
        <el-form-item label="标题">
          <el-input
            v-model="searchForm.title"
            placeholder="请输入标题"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="searchForm.enabled"
            placeholder="请选择状态"
            clearable
            style="width: 150px"
          >
            <el-option label="已启用" :value="true" />
            <el-option label="已禁用" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">搜索</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
        <el-form-item v-if="isLoggedIn">
          <el-button type="success" @click="handleCreate">
            <el-icon><Plus /></el-icon>
            新建日志监控
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 温馨提示 -->
      <el-alert
        title="温馨提示：监控任务将按配置的采集间隔自动执行，修改后请及时保存"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 20px"
      />

      <!-- 表格 -->
      <el-table v-loading="loading" :data="configList" stripe style="width: 100%">
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
        <el-table-column label="监控时间" min-width="160">
          <template #default="{ row }">
            {{ row.startTime }} - {{ row.endTime }}
          </template>
        </el-table-column>
        <el-table-column prop="collectionIntervalDisplay" label="采集间隔" min-width="100" />
        <el-table-column prop="alertThreshold" label="告警阈值" min-width="100" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-switch
              v-if="isLoggedIn"
              v-model="row.enabled"
              @change="handleToggleEnabled(row)"
            />
            <el-tag v-else :type="row.enabled ? 'success' : 'info'">
              {{ row.enabled ? '已启用' : '已禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <span class="action-link view-link" @click="handleViewDashboard(row)">查看数据</span>
            <span v-if="isLoggedIn" class="action-link edit-link" @click="handleEdit(row)">编辑</span>
            <span v-if="isLoggedIn" class="action-link delete-link" @click="handleDelete(row.id, row.title)">删除</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination">
        <el-pagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.size"
          :total="pagination.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </el-card>

    <!-- 创建/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑监控配置' : '新建监控配置'"
      width="600px"
      @close="handleDialogClose"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="formRules"
        label-width="140px"
      >
        <el-form-item label="监控标题" prop="title">
          <el-input v-model="form.title" placeholder="请输入监控标题" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="备注说明" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            placeholder="请输入备注说明"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="SLS模板" prop="keywordTemplateId">
          <el-select
            v-model="form.keywordTemplateId"
            placeholder="请选择SLS模板"
            filterable
            remote
            :remote-method="handleSearchSlsKeyword"
            :loading="slsKeywordLoading"
            style="width: 100%"
            @visible-change="handleSlsKeywordVisibleChange"
          >
            <el-option
              v-for="keyword in slsKeywordList"
              :key="keyword.id"
              :label="`【${keyword.application || ''}】${keyword.desc}`"
              :value="keyword.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="监控时间" required>
          <div class="time-range-group">
            <el-time-picker
              v-model="startTimeValue"
              placeholder="开始时间"
              format="HH:mm:ss"
              value-format="HH:mm:ss"
              style="width: 100%"
            />
            <span class="time-separator">至</span>
            <el-time-picker
              v-model="endTimeValue"
              placeholder="结束时间"
              format="HH:mm:ss"
              value-format="HH:mm:ss"
              style="width: 100%"
            />
          </div>
        </el-form-item>
        <el-form-item label="采集间隔" prop="collectionInterval">
          <el-select v-model="form.collectionInterval" placeholder="请选择采集间隔" style="width: 100%">
            <el-option label="10秒" :value="10" />
            <el-option label="15秒" :value="15" />
            <el-option label="30秒" :value="30" />
            <el-option label="1分钟" :value="60" />
            <el-option label="5分钟" :value="300" />
            <el-option label="10分钟" :value="600" />
          </el-select>
        </el-form-item>
        <el-form-item label="告警阈值" prop="alertThreshold">
          <el-input-number
            v-model="form.alertThreshold"
            :min="1"
            placeholder="请输入告警阈值"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="告警通知Webhook" prop="alertWebhook">
          <el-input
            v-model="form.alertWebhook"
            placeholder="请输入钉钉机器人Webhook地址（选填）"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  createAlertConfig,
  updateAlertConfig,
  deleteAlertConfig,
  getAlertConfigList,
  getAlertConfigDetail,
  enableAlertConfig,
  disableAlertConfig
} from '@/api/alert.js'
import { getSlsKeywordList } from '@/api/sls-keyword-management.js'
import { useAuthStore } from '@/stores/auth.js'

const router = useRouter()
const authStore = useAuthStore()
const isLoggedIn = computed(() => authStore.isLoggedIn)

const loading = ref(false)
const configList = ref([])
const slsKeywordList = ref([])
const slsKeywordLoading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref(null)

const searchForm = reactive({
  title: '',
  enabled: undefined
})

const slsKeywordPagination = reactive({
  current: 1,
  size: 20,
  total: 0
})

const pagination = reactive({
  current: 1,
  size: 10,
  total: 0
})

const form = reactive({
  title: '',
  description: '',
  keywordTemplateId: '',
  startTime: '00:00:00',
  endTime: '23:59:59',
  collectionInterval: 60,
  alertThreshold: 50,
  alertWebhook: ''
})

const startTimeValue = computed({
  get: () => form.startTime,
  set: (val) => { form.startTime = val }
})

const endTimeValue = computed({
  get: () => form.endTime,
  set: (val) => { form.endTime = val }
})

const formRules = {
  title: [{ required: true, message: '请输入监控标题', trigger: 'blur' }],
  keywordTemplateId: [{ required: true, message: '请选择SLS模板', trigger: 'change' }],
  startTime: [{ required: true, message: '请选择监控开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择监控结束时间', trigger: 'change' }],
  collectionInterval: [{ required: true, message: '请选择采集间隔', trigger: 'change' }],
  alertThreshold: [{ required: true, message: '请输入告警阈值', trigger: 'blur' }]
}

const loadConfigList = async () => {
  loading.value = true
  try {
    const res = await getAlertConfigList({
      ...searchForm,
      current: pagination.current,
      size: pagination.size
    })
    if (res.code === 200) {
      configList.value = res.data.records || res.data.list || []
      pagination.total = res.data.total
    }
  } finally {
    loading.value = false
  }
}

const loadSlsKeywordList = async (append = false) => {
  if (slsKeywordLoading.value) return
  slsKeywordLoading.value = true
  try {
    const res = await getSlsKeywordList({
      current: slsKeywordPagination.current,
      size: slsKeywordPagination.size
    })
    if (res.code === 200) {
      const list = res.data.records || res.data.list || []
      if (append) {
        slsKeywordList.value = [...slsKeywordList.value, ...list]
      } else {
        slsKeywordList.value = list
      }
      slsKeywordPagination.total = res.data.total || 0
    }
  } catch (error) {
    console.error('加载SLS模板列表失败:', error)
  } finally {
    slsKeywordLoading.value = false
  }
}

const handleSearchSlsKeyword = async (query) => {
  if (!query || query.trim() === '') {
    slsKeywordPagination.current = 1
    await loadSlsKeywordList()
    return
  }
  slsKeywordLoading.value = true
  try {
    const res = await getSlsKeywordList({
      current: 1,
      size: 100,
      desc: query
    })
    if (res.code === 200) {
      slsKeywordList.value = res.data.records || res.data.list || []
      slsKeywordPagination.total = slsKeywordList.value.length
    }
  } catch (error) {
    console.error('搜索SLS模板列表失败:', error)
  } finally {
    slsKeywordLoading.value = false
  }
}

const handleSlsKeywordVisibleChange = (visible) => {
  if (visible && slsKeywordList.value.length === 0) {
    slsKeywordPagination.current = 1
    loadSlsKeywordList()
  }
}

const loadMoreSlsKeywords = () => {
  if (slsKeywordList.value.length >= slsKeywordPagination.total) return
  slsKeywordPagination.current++
  loadSlsKeywordList(true)
}

const handleSearch = () => {
  pagination.current = 1
  loadConfigList()
}

const handleReset = () => {
  searchForm.title = ''
  searchForm.enabled = undefined
  pagination.current = 1
  loadConfigList()
}

const handleCreate = () => {
  isEdit.value = false
  resetForm()
  dialogVisible.value = true
}

const handleEdit = async (row) => {
  isEdit.value = true
  try {
    const res = await getAlertConfigDetail(row.id)
    if (res.code === 200) {
      Object.assign(form, res.data)
      dialogVisible.value = true
    }
  } catch (error) {
    ElMessage.error('加载配置详情失败')
  }
}

const handleDelete = (id, title) => {
  ElMessageBox.confirm(
    `确定要删除监控配置"${title}"吗？`,
    '提示',
    {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }
  ).then(async () => {
    try {
      const res = await deleteAlertConfig(id)
      if (res.code === 200) {
        ElMessage.success('删除成功')
        loadConfigList()
      }
    } catch (error) {
      ElMessage.error('删除失败')
    }
  })
}

const handleToggleEnabled = async (row) => {
  try {
    if (row.enabled) {
      await enableAlertConfig(row.id)
      ElMessage.success('已启用')
    } else {
      await disableAlertConfig(row.id)
      ElMessage.success('已禁用')
    }
    loadConfigList()
  } catch (error) {
    row.enabled = !row.enabled
    ElMessage.error('操作失败')
  }
}

const handleViewDashboard = (row) => {
  router.push({
    path: '/alert-dashboard',
    query: { configId: row.id.toString() }
  })
}

const handleSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      if (isEdit.value) {
        await updateAlertConfig(form.id, form)
        ElMessage.success('更新成功')
      } else {
        const res = await createAlertConfig(form)
        if (res.code === 200) {
          ElMessage.success('创建成功')
        }
      }
      dialogVisible.value = false
      loadConfigList()
    } catch (error) {
      ElMessage.error('操作失败')
    } finally {
      submitting.value = false
    }
  })
}

const handleDialogClose = () => {
  resetForm()
}

const resetForm = () => {
  formRef.value?.resetFields()
  Object.assign(form, {
    title: '',
    description: '',
    keywordTemplateId: '',
    startTime: '00:00:00',
    endTime: '23:59:59',
    collectionInterval: 60,
    alertThreshold: 50,
    alertWebhook: ''
  })
}

const handleSizeChange = () => {
  pagination.current = 1
  loadConfigList()
}

const handleCurrentChange = () => {
  loadConfigList()
}

onMounted(() => {
  loadConfigList()
  loadSlsKeywordList()
})
</script>

<style scoped>
.alert-config-management {
  padding: 0;
}

.alert-config-management > .el-card {
  border-radius: 4px;
  border: 1px solid #e4e7ed;
}

.search-form :deep(.el-form-item) {
  margin-bottom: 0;
  margin-right: 12px;
}

.pagination {
  margin-top: 20px;
  display: flex;
  justify-content: center;
}

.action-link {
  cursor: pointer;
  margin-right: 12px;
  font-size: 12px;
  transition: all 0.2s ease;
}

.action-link:last-child {
  margin-right: 0;
}

.view-link {
  color: #67c23a;
}

.view-link:hover {
  color: #059669;
  text-decoration: underline;
}

.edit-link {
  color: #1890ff;
}

.edit-link:hover {
  color: #2563eb;
  text-decoration: underline;
}

.delete-link {
  color: #f56c6c;
}

.delete-link:hover {
  color: #e11d48;
  text-decoration: underline;
}

.time-range-group {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}

.time-separator {
  color: #606266;
  flex-shrink: 0;
}
</style>
