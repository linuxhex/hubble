<template>
  <div class="sls-keyword-management">
    <!-- 搜索条件区域 -->
    <div class="search-area">
      <el-form :model="searchForm" inline class="search-form">
        <el-form-item label="描述" class="no-margin">
          <el-input
            v-model="searchForm.desc"
            placeholder="请输入描述"
            clearable
            size="small"
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="归属应用" class="no-margin">
          <el-select
            v-model="searchForm.application"
            placeholder="请选择应用"
            clearable
            filterable
            size="small"
            style="width: 200px"
          >
            <el-option
              v-for="app in applicationList"
              :key="app"
              :label="app"
              :value="app"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="标签" class="no-margin">
          <el-select
            v-model="searchForm.tag"
            placeholder="请选择标签"
            clearable
            filterable
            size="small"
            style="width: 200px"
          >
            <el-option
              v-for="tag in tagList"
              :key="tag"
              :label="tag"
              :value="tag"
            />
          </el-select>
        </el-form-item>
        <el-form-item class="no-margin operation-buttons">
          <el-button type="primary" size="small" @click="handleSearch">搜索</el-button>
          <el-button size="small" @click="handleReset">重置</el-button>
          <el-button v-if="isLoggedIn" size="small" @click="handleAddApplication">
            <el-icon><FolderAdd /></el-icon>
            新增应用
          </el-button>
          <el-button v-if="isLoggedIn" size="small" @click="handleAddTag">
            <el-icon><PriceTag /></el-icon>
            新增标签
          </el-button>
          <el-button v-if="isLoggedIn" type="primary" size="small" @click="handleCreate">
            <el-icon><Plus /></el-icon>
            新建模版
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 温馨提示 -->
    <div class="tip-area">
      <el-alert
        title="温馨提示：SLS 关键字用于日志查询模板，修改后即时生效"
        type="info"
        :closable="false"
        show-icon
      />
    </div>

    <!-- 表格 -->
    <div class="table-area">
      <el-table v-loading="loading" :data="keywordList" stripe size="small" border>
        <el-table-column prop="desc" label="描述" width="200" show-overflow-tooltip />
        <el-table-column prop="keywords" label="查询关键字" min-width="200" show-overflow-tooltip />
        <el-table-column prop="application" label="归属应用" width="200" />
        <el-table-column prop="logstore" label="Logstore" width="150" show-overflow-tooltip />
        <el-table-column prop="tags" label="标签" width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button v-if="isLoggedIn" link type="primary" size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button v-if="isLoggedIn" link type="danger" size="small" @click="handleDelete(row.id, row.desc)">删除</el-button>
            <el-button link type="success" size="small" @click="handleQuickQuery(row)">一键查询</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          size="small"
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </div>

    <!-- 编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="700px"
      @close="handleDialogClose"
    >
      <el-form
        ref="dialogFormRef"
        :model="dialogForm"
        :rules="dialogRules"
        label-width="120px"
      >
        <el-form-item label="描述" prop="desc">
          <el-input
            v-model="dialogForm.desc"
            type="textarea"
            :rows="3"
            placeholder="请输入描述"
            maxlength="500"
            show-word-limit
          />
          <div class="form-tip">描述将自动转换为向量（text-embedding-v3模型，维度1024）</div>
        </el-form-item>
        <el-form-item label="查询关键字" prop="keywords">
          <el-input
            v-model="dialogForm.keywords"
            type="textarea"
            :rows="3"
            placeholder="请输入查询关键字"
            maxlength="1000"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="归属应用">
          <el-select
            v-model="dialogForm.application"
            placeholder="请选择应用"
            clearable
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="app in applicationList"
              :key="app"
              :label="app"
              :value="app"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="Logstore">
          <el-input
            v-model="dialogForm.logstore"
            placeholder="请输入Logstore"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="标签">
          <el-select
            v-model="selectedTags"
            placeholder="请选择标签"
            multiple
            filterable
            allow-create
            style="width: 100%"
          >
            <el-option
              v-for="tag in tagList"
              :key="tag"
              :label="tag"
              :value="tag"
            />
          </el-select>
          <div class="form-tip">可多选，多个标签以英文逗号分隔存储</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleDialogSubmit" :loading="submitting">
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- 新增应用对话框 -->
    <el-dialog
      v-model="applicationDialogVisible"
      title="新增应用"
      width="400px"
      @close="handleApplicationDialogClose"
    >
      <el-form
        ref="applicationFormRef"
        :model="applicationForm"
        :rules="applicationRules"
        label-width="80px"
      >
        <el-form-item label="应用名称" prop="value">
          <el-input
            v-model="applicationForm.value"
            placeholder="请输入应用名称"
            maxlength="100"
            show-word-limit
            clearable
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applicationDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleApplicationSubmit" :loading="applicationSubmitting">
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- 新增标签对话框 -->
    <el-dialog
      v-model="tagDialogVisible"
      title="新增标签"
      width="400px"
      @close="handleTagDialogClose"
    >
      <el-form
        ref="tagFormRef"
        :model="tagForm"
        :rules="tagRules"
        label-width="80px"
      >
        <el-form-item label="标签名称" prop="value">
          <el-input
            v-model="tagForm.value"
            placeholder="请输入标签名称"
            maxlength="100"
            show-word-limit
            clearable
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tagDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleTagSubmit" :loading="tagSubmitting">
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- 一键查询变量输入对话框 -->
    <el-dialog
      v-model="queryDialogVisible"
      :title="`一键查询 - ${currentQueryRow?.desc || ''}`"
      width="600px"
    >
      <el-form :model="queryForm" label-width="120px">
        <el-form-item
          v-for="variable in queryVariables"
          :key="variable"
          :label="variable"
        >
          <el-input
            v-model="queryForm.variables[variable]"
            :placeholder="`请输入${variable}的值`"
            clearable
          />
        </el-form-item>
        <el-form-item v-if="queryVariables.length === 0">
          <el-alert
            type="info"
            :closable="false"
            show-icon
          >
            该查询关键字不包含变量，将直接跳转到 SLS 控制台
          </el-alert>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="queryDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleQuerySubmit">查询</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, FolderAdd, PriceTag } from '@element-plus/icons-vue'
import {
  getSlsKeywordList,
  getSlsKeywordDetail,
  createSlsKeyword,
  updateSlsKeyword,
  deleteSlsKeyword,
  getApplicationList,
  getTagList,
  addApplication,
  addTag
} from '@/api/sls-keyword-management'
import { showSuccess, showError, confirm } from '@/utils/message'
import { generateSlsLink } from '@/utils/sls'
import { nowInSeconds } from '@/utils/timestamp'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const isLoggedIn = computed(() => authStore.isLoggedIn)

const keywordList = ref([])
const total = ref(0)
const loading = ref(false)
const submitting = ref(false)

const searchForm = ref({
  desc: '',
  application: '',
  tag: ''
})

const pagination = ref({
  page: 1,
  pageSize: 10
})

const dialogVisible = ref(false)
const dialogTitle = ref('新建模版')
const dialogFormRef = ref(null)
const isEdit = ref(false)
const editId = ref(null)

const dialogForm = ref({
  desc: '',
  keywords: '',
  application: '',
  logstore: '',
  tags: ''
})

const selectedTags = ref([])

const applicationList = ref([])
const tagList = ref([])

const applicationDialogVisible = ref(false)
const applicationFormRef = ref(null)
const applicationSubmitting = ref(false)
const applicationForm = ref({ value: '' })
const applicationRules = {
  value: [
    { required: true, message: '请输入应用名称', trigger: 'blur' },
    { max: 100, message: '应用名称长度不能超过100字符', trigger: 'blur' }
  ]
}

const tagDialogVisible = ref(false)
const tagFormRef = ref(null)
const tagSubmitting = ref(false)
const tagForm = ref({ value: '' })
const tagRules = {
  value: [
    { required: true, message: '请输入标签名称', trigger: 'blur' },
    { max: 100, message: '标签名称长度不能超过100字符', trigger: 'blur' }
  ]
}

const queryDialogVisible = ref(false)
const currentQueryRow = ref(null)
const queryVariables = ref([])
const queryForm = ref({
  variables: {}
})

const dialogRules = {
  desc: [
    { required: true, message: '请输入描述', trigger: 'blur' },
    { max: 500, message: '描述长度不能超过500字符', trigger: 'blur' }
  ],
  keywords: [
    { required: true, message: '请输入查询关键字', trigger: 'blur' },
    { max: 1000, message: '查询关键字长度不能超过1000字符', trigger: 'blur' }
  ]
}

// 从查询关键字中提取变量
const extractVariables = (keywords) => {
  if (!keywords) return []
  const variablePattern = /\{([a-zA-Z0-9_]+)\}/g
  const variables = []
  let match
  while ((match = variablePattern.exec(keywords)) !== null) {
    const varName = match[1]
    if (!variables.includes(varName)) {
      variables.push(varName)
    }
  }
  return variables
}

// 替换查询关键字中的变量
const replaceVariables = (keywords, variables) => {
  let result = keywords
  for (const [key, value] of Object.entries(variables)) {
    const regex = new RegExp(`\\{${key}\\}`, 'g')
    result = result.replace(regex, value)
  }
  return result
}

// 加载列表
const loadList = async () => {
  loading.value = true
  try {
    const res = await getSlsKeywordList({
      page: pagination.value.page,
      pageSize: pagination.value.pageSize,
      desc: searchForm.value.desc || undefined,
      application: searchForm.value.application || undefined,
      tag: searchForm.value.tag || undefined
    })
    keywordList.value = res.data.list || []
    total.value = res.data.total || 0
  } catch (error) {
    showError(error.message || '加载失败')
  } finally {
    loading.value = false
  }
}

// 加载应用列表
const loadApplicationList = async () => {
  try {
    const res = await getApplicationList()
    applicationList.value = res.data || []
  } catch (error) {
    console.error('加载应用列表失败', error)
  }
}

// 加载标签列表
const loadTagList = async () => {
  try {
    const res = await getTagList()
    tagList.value = res.data || []
  } catch (error) {
    console.error('加载标签列表失败', error)
  }
}

const handleSearch = () => {
  pagination.value.page = 1
  loadList()
}

const handleReset = () => {
  searchForm.value.desc = ''
  searchForm.value.application = ''
  searchForm.value.tag = ''
  handleSearch()
}

const handleCreate = () => {
  isEdit.value = false
  editId.value = null
  dialogTitle.value = '新建模版'
  dialogForm.value = {
    desc: '',
    keywords: '',
    application: '',
    logstore: '',
    tags: ''
  }
  selectedTags.value = []
  dialogVisible.value = true
}

const handleEdit = async (row) => {
  isEdit.value = true
  editId.value = row.id
  dialogTitle.value = '编辑模版'
  
  try {
    const res = await getSlsKeywordDetail(row.id)
    const data = res.data
    dialogForm.value = {
      desc: data.desc,
      keywords: data.keywords,
      application: data.application || '',
      logstore: data.logstore || '',
      tags: data.tags || ''
    }
    
    if (data.tags) {
      selectedTags.value = data.tags.split(',').map((tag) => tag.trim()).filter((tag) => tag)
    } else {
      selectedTags.value = []
    }
    
    dialogVisible.value = true
  } catch (error) {
    showError(error.message || '加载详情失败')
  }
}

const handleDelete = async (id, desc) => {
  try {
    await confirm(`确定要删除模版"${desc}"吗？删除后将无法恢复。`, '确认删除')
    await deleteSlsKeyword(id)
    showSuccess('删除成功')
    loadList()
  } catch (error) {
    if (error.message !== '用户取消操作') {
      showError(error.message || '删除失败')
    }
  }
}

const handleDialogSubmit = async () => {
  if (!dialogFormRef.value) return

  await dialogFormRef.value.validate(async (valid) => {
    if (!valid) return

    dialogForm.value.tags = selectedTags.value.join(',')

    submitting.value = true
    try {
      if (isEdit.value && editId.value) {
        await updateSlsKeyword(editId.value, dialogForm.value)
        showSuccess('更新成功')
        dialogVisible.value = false
        loadList()
      } else {
        await createSlsKeyword(dialogForm.value)
        showSuccess('创建成功')
        dialogVisible.value = false
        pagination.value.page = 1
        loadList()
      }
    } catch (error) {
      showError(error.message || '保存失败')
    } finally {
      submitting.value = false
    }
  })
}

const handleDialogClose = () => {
  dialogFormRef.value?.resetFields()
  selectedTags.value = []
}

const handleSizeChange = () => {
  loadList()
}

const handlePageChange = () => {
  loadList()
}

const handleAddApplication = () => {
  applicationDialogVisible.value = true
}

const handleApplicationSubmit = async () => {
  if (!applicationFormRef.value) return

  await applicationFormRef.value.validate(async (valid) => {
    if (!valid) return

    applicationSubmitting.value = true
    try {
      await addApplication(applicationForm.value.value)
      showSuccess('新增应用成功')
      applicationDialogVisible.value = false
      await loadApplicationList()
    } catch (error) {
      showError(error.message || '新增失败')
    } finally {
      applicationSubmitting.value = false
    }
  })
}

const handleApplicationDialogClose = () => {
  applicationFormRef.value?.resetFields()
  applicationForm.value = { value: '' }
}

const handleAddTag = () => {
  tagDialogVisible.value = true
}

const handleTagSubmit = async () => {
  if (!tagFormRef.value) return

  await tagFormRef.value.validate(async (valid) => {
    if (!valid) return

    tagSubmitting.value = true
    try {
      await addTag(tagForm.value.value)
      showSuccess('新增标签成功')
      tagDialogVisible.value = false
      await loadTagList()
    } catch (error) {
      showError(error.message || '新增失败')
    } finally {
      tagSubmitting.value = false
    }
  })
}

const handleTagDialogClose = () => {
  tagFormRef.value?.resetFields()
  tagForm.value = { value: '' }
}

const handleQuickQuery = (row) => {
  currentQueryRow.value = row
  queryVariables.value = extractVariables(row.keywords || '')
  queryForm.value = {
    variables: {}
  }
  queryVariables.value.forEach(variable => {
    queryForm.value.variables[variable] = ''
  })
  queryDialogVisible.value = true
}

const handleQuerySubmit = () => {
  if (!currentQueryRow.value) return

  const missingVariables = []
  queryVariables.value.forEach(variable => {
    if (!queryForm.value.variables[variable] || queryForm.value.variables[variable].trim() === '') {
      missingVariables.push(variable)
    }
  })

  if (missingVariables.length > 0) {
    showError(`请填写以下变量：${missingVariables.join('、')}`)
    return
  }

  let queryString = currentQueryRow.value.keywords || ''
  if (queryVariables.value.length > 0) {
    queryString = replaceVariables(queryString, queryForm.value.variables)
  }

  const endTime = nowInSeconds()
  const startTime = endTime - 5 * 24 * 60 * 60

  const slsLink = generateSlsLink('', {
    logstore: currentQueryRow.value.logstore || 'all',
    queryString: queryString,
    startTime: startTime,
    endTime: endTime
  })

  if (slsLink) {
    window.open(slsLink, '_blank')
    queryDialogVisible.value = false
  } else {
    showError('生成 SLS 链接失败')
  }
}

onMounted(() => {
  loadList()
  loadApplicationList()
  loadTagList()
})
</script>

<style scoped>
.sls-keyword-management {
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

.tip-area {
  background: white;
  padding: 12px;
  border-radius: 4px;
}

.table-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: white;
  border-radius: 4px;
  padding: 16px;
  overflow: hidden;
}

.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
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


