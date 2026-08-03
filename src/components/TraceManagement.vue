<template>
  <div class="trace-management">
    <!-- 列表视图 -->
    <div v-if="!showForm">
      <!-- 搜索条件区域 -->
      <div class="search-area">
        <div class="header-actions">
          <span class="page-title">链路配置</span>
          <div>
            <el-button v-if="isLoggedIn" size="small" @click="handleCreateCategory">
              <el-icon><FolderAdd /></el-icon>
              新建业务分类
            </el-button>
            <el-button v-if="isLoggedIn" type="primary" size="small" @click="handleCreate" style="margin-left: 10px">
              <el-icon><Plus /></el-icon>
              新建业务链路
            </el-button>
          </div>
        </div>

        <el-form :model="searchForm" inline class="search-form">
          <el-form-item label="关键字" class="no-margin">
            <el-input
              v-model="searchForm.keyword"
              placeholder="请输入业务名称"
              clearable
              size="small"
              style="width: 200px"
              @keyup.enter="handleSearch"
            />
          </el-form-item>
          <el-form-item label="业务分类" class="no-margin">
            <el-select
              v-model="searchForm.category"
              placeholder="请选择业务分类"
              clearable
              filterable
              size="small"
              style="width: 200px"
            >
              <el-option
                v-for="category in categoryOptions"
                :key="category"
                :label="category"
                :value="category"
              />
            </el-select>
          </el-form-item>
          <el-form-item class="no-margin operation-buttons">
            <el-button type="primary" size="small" @click="handleSearch">搜索</el-button>
            <el-button size="small" @click="handleReset">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- 表格 -->
      <div class="table-area">
        <el-table v-loading="loading" :data="traceList" stripe size="small" border>
          <el-table-column prop="name" label="业务名称" width="200" />
          <el-table-column prop="categoryName" label="业务分类" width="200" />
          <el-table-column prop="description" label="描述" min-width="250" show-overflow-tooltip />
          <el-table-column prop="nodeCount" label="节点数量" width="100" align="center" />
          <el-table-column prop="createdAt" label="创建时间" width="200" />
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button v-if="isLoggedIn" link type="primary" size="small" @click="handleEdit(row.id)">编辑</el-button>
              <el-button v-if="isLoggedIn" link type="danger" size="small" @click="handleDelete(row.id, row.name)">删除</el-button>
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
    </div>

    <!-- 表单视图 -->
    <div v-else class="form-area">
      <div class="header-actions">
        <span class="page-title">{{ isEdit ? '编辑业务链路' : '新建业务链路' }}</span>
        <el-button size="small" @click="handleCancel">返回</el-button>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="120px"
        @submit.prevent
      >
        <el-form-item label="业务分类" prop="category">
          <el-select
            v-model="form.category"
            placeholder="请选择业务分类"
            clearable
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="category in categoryOptions"
              :key="category"
              :label="category"
              :value="category"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="业务名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入业务名称" maxlength="50" show-word-limit />
        </el-form-item>

        <el-form-item label="业务描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="请输入业务描述"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="节点配置" prop="nodes">
          <div class="nodes-container">
            <div class="tree-toolbar">
              <el-button type="primary" size="small" @click="handleAddRootNode">
                <el-icon><Plus /></el-icon>
                添加顶级节点
              </el-button>
              <el-text type="info" size="small">
                <el-icon><InfoFilled /></el-icon>
                最多支持一级子节点，支持拖拽排序
              </el-text>
            </div>

            <div v-if="treeData.length === 0" class="empty-nodes">
              <el-empty description="请至少添加一个节点" :image-size="100" />
            </div>

            <el-tree
              v-else
              :data="treeData"
              node-key="key"
              default-expand-all
              :expand-on-click-node="false"
              draggable
              :allow-drop="handleAllowDrop"
              @node-drop="handleNodeDrop"
              class="node-tree"
            >
              <template #default="{ data }">
                <div class="tree-node">
                  <div class="node-info">
                    <el-icon class="drag-handle"><Rank /></el-icon>
                    <el-icon class="node-icon"><Document /></el-icon>
                    <span class="node-name">{{ data.name || '未命名节点' }}</span>
                    <el-tag v-if="data.parentId == null" type="primary" size="small">顶级</el-tag>
                    <el-tag v-else type="info" size="small">子节点</el-tag>
                    <span v-if="data.description" class="node-desc">{{ data.description }}</span>
                  </div>
                  <div class="node-actions">
                    <el-button
                      v-if="data.parentId == null"
                      link
                      type="primary"
                      size="small"
                      @click="handleAddChildNode(data)"
                    >
                      <el-icon><Plus /></el-icon>
                      添加子节点
                    </el-button>
                    <el-button
                      v-if="data.parentId == null && (!data.children || data.children.length === 0)"
                      link
                      type="primary"
                      size="small"
                      @click="handleSetAsChild(data)"
                    >
                      <el-icon><Bottom /></el-icon>
                      设为子节点
                    </el-button>
                    <el-button
                      v-if="data.parentId != null"
                      link
                      type="primary"
                      size="small"
                      @click="handleSetAsRoot(data)"
                    >
                      <el-icon><Top /></el-icon>
                      设为顶级节点
                    </el-button>
                    <el-button
                      link
                      type="primary"
                      size="small"
                      @click="handleEditNode(data)"
                    >
                      <el-icon><Edit /></el-icon>
                      编辑
                    </el-button>
                    <el-button
                      link
                      type="danger"
                      size="small"
                      @click="handleDeleteNode(data)"
                    >
                      <el-icon><Delete /></el-icon>
                      删除
                    </el-button>
                  </div>
                </div>
              </template>
            </el-tree>
          </div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" @click="handleSubmit" :loading="submitting">
            保存
          </el-button>
          <el-button @click="handleCancel">取消</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 新建分类对话框 -->
    <el-dialog
      v-model="categoryDialogVisible"
      title="新建业务分类"
      width="500px"
      @close="handleCategoryDialogClose"
    >
      <el-form
        ref="categoryFormRef"
        :model="categoryForm"
        :rules="categoryRules"
        label-width="100px"
      >
        <el-form-item label="分类名称" prop="value">
          <el-input v-model="categoryForm.value" placeholder="请输入分类名称" maxlength="50" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="categoryDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCategorySubmit" :loading="categorySubmitting">
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- 选择父节点对话框 -->
    <el-dialog
      v-model="parentSelectionDialogVisible"
      title="选择父节点"
      width="400px"
      @close="handleCloseParentSelection"
    >
      <el-form label-width="100px">
        <el-form-item label="父节点">
          <el-select
            v-model="selectedParentIndex"
            placeholder="请选择父节点"
            style="width: 100%"
          >
            <el-option
              v-for="{ node, index } in availableParentNodes"
              :key="index"
              :label="node.name || `节点 ${index + 1}`"
              :value="index"
            />
          </el-select>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="handleCloseParentSelection">取消</el-button>
        <el-button type="primary" @click="handleConfirmParentSelection" :disabled="selectedParentIndex === null">
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- 节点编辑对话框 -->
    <el-dialog
      v-model="nodeDialogVisible"
      :title="nodeDialogTitle"
      width="600px"
      @close="handleDialogClose"
    >
      <el-form
        ref="nodeFormRef"
        :model="currentNode"
        :rules="nodeRules"
        label-width="120px"
      >
        <el-form-item label="节点名称" prop="name">
          <el-input v-model="currentNode.name" placeholder="请输入节点名称" maxlength="30" />
        </el-form-item>

        <el-form-item label="节点描述" prop="description">
          <el-input
            v-model="currentNode.description"
            placeholder="请输入节点描述"
            maxlength="100"
          />
        </el-form-item>

        <el-form-item label="SLS日志库" prop="slsLogstore">
          <el-input v-model="currentNode.slsLogstore" placeholder="请输入SLS日志库名称" />
          <div class="form-hint">
            SLS项目使用全局配置，无需在此填写
          </div>
        </el-form-item>

        <el-form-item label="查询模板" prop="queryTemplate">
          <el-input
            v-model="currentNode.queryTemplate"
            type="textarea"
            :rows="3"
            placeholder="例如: order_id:{orderId} AND status:created"
          />
          <div class="template-hint">
            使用 {变量名} 格式定义变量，变量名只能包含字母、数字和下划线
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="nodeDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSaveNode">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Document, InfoFilled, Rank, Bottom, Top, FolderAdd } from '@element-plus/icons-vue'
import { createTrace, updateTrace, getTraceDetail, getAllCategories, createCategory, getTraceList, deleteTrace } from '@/api/trace-management'
import { showSuccess, showError, confirm } from '@/utils/message'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const isLoggedIn = computed(() => authStore.isLoggedIn)

const route = useRoute()

// 视图切换
const showForm = ref(false)

// 列表相关
const traceList = ref([])
const total = ref(0)
const loading = ref(false)

const searchForm = ref({
  keyword: '',
  category: undefined
})

const categoryOptions = ref([])

const pagination = ref({
  page: 1,
  pageSize: 10
})

// 表单相关
const formRef = ref(null)
const nodeFormRef = ref(null)
const submitting = ref(false)
const isEdit = ref(false)
const traceId = ref(null)

const form = reactive({
  name: '',
  description: '',
  category: undefined,
  nodes: []
})

// 树形节点数据
const availableParentNodes = computed(() => {
  if (selectedNodeForParent.value === -1) return []
  
  return form.nodes
    .map((node, idx) => ({ node, index: idx }))
    .filter(({ node, index: idx }) => node.parentId == null && idx !== selectedNodeForParent.value)
})

const treeData = computed(() => {
  // 构建树形结构
  const rootNodes = []
  const nodeMap = new Map()

  // 第一遍：创建所有节点的映射
  form.nodes.forEach((node, index) => {
    const treeNode = {
      ...node,
      key: `node-${index}`,
      children: []
    }
    nodeMap.set(index, treeNode)
  })

  // 第二遍：构建树形结构
  form.nodes.forEach((node, index) => {
    const treeNode = nodeMap.get(index)
    if (node.parentId == null) {
      // 顶级节点
      rootNodes.push(treeNode)
    } else {
      // 子节点
      const parentNode = nodeMap.get(node.parentId)
      if (parentNode && parentNode.children) {
        parentNode.children.push(treeNode)
      }
    }
  })

  return rootNodes
})

// 节点对话框
const nodeDialogVisible = ref(false)
const nodeDialogTitle = ref('')
const currentNode = ref({
  name: '',
  description: '',
  slsLogstore: 'all',
  queryTemplate: '',
  nodeOrder: 0,
  parentId: null
})
const currentNodeIndex = ref(-1)
const isAddingChild = ref(false)
const parentNodeForChild = ref(null)

// 选择父节点对话框
const parentSelectionDialogVisible = ref(false)
const selectedNodeForParent = ref(-1)
const selectedParentIndex = ref(null)

// 分类对话框相关
const categoryDialogVisible = ref(false)
const categoryFormRef = ref(null)
const categorySubmitting = ref(false)
const categoryForm = ref({
  value: ''
})

const categoryRules = {
  value: [
    { required: true, message: '请输入分类名称', trigger: 'blur' },
    { max: 50, message: '分类名称长度不能超过50字符', trigger: 'blur' }
  ]
}

const rules = {
  name: [
    { required: true, message: '请输入业务名称', trigger: 'blur' },
    { min: 1, max: 50, message: '业务名称长度必须在1-50之间', trigger: 'blur' }
  ],
  description: [
    { max: 200, message: '描述长度不能超过200字符', trigger: 'blur' }
  ],
  category: [
    { required: true, message: '请选择业务分类', trigger: 'change' }
  ],
  nodes: [
    { required: true, type: 'array', min: 1, message: '至少需要配置一个节点', trigger: 'change' }
  ]
}

const nodeRules = {
  name: [
    { required: true, message: '请输入节点名称', trigger: 'blur' },
    { min: 1, max: 30, message: '节点名称长度必须在1-30之间', trigger: 'blur' }
  ],
  slsLogstore: [
    { required: true, message: '请输入SLS日志库名称', trigger: 'blur' },
    { max: 100, message: 'SLS日志库名称长度不能超过100字符', trigger: 'blur' }
  ],
  queryTemplate: [
    { required: true, message: '请输入查询模板', trigger: 'blur' }
  ]
}

// ========== 列表相关方法 ==========

// 加载分类列表
const loadCategories = async () => {
  try {
    const res = await getAllCategories()
    categoryOptions.value = res.data
  } catch (error) {
    console.error('加载分类列表失败:', error)
  }
}

// 加载列表
const loadList = async () => {
  loading.value = true
  try {
    const res = await getTraceList({
      page: pagination.value.page,
      pageSize: pagination.value.pageSize,
      keyword: searchForm.value.keyword || undefined,
      category: searchForm.value.category
    })
    traceList.value = res?.data?.list || []
    total.value = res?.data?.total || 0
  } catch (error) {
    showError(error.message || '加载失败')
  } finally {
    loading.value = false
  }
}

// 搜索
const handleSearch = () => {
  pagination.value.page = 1
  loadList()
}

// 重置
const handleReset = () => {
  searchForm.value.keyword = ''
  searchForm.value.category = undefined
  handleSearch()
}

// 新建
const handleCreate = () => {
  isEdit.value = false
  traceId.value = null
  form.name = ''
  form.description = ''
  form.category = undefined
  form.nodes = []
  showForm.value = true
}

// 编辑
const handleEdit = async (id) => {
  isEdit.value = true
  traceId.value = id
  showForm.value = true
  await loadDetail(id)
}

// 删除
const handleDelete = async (id, name) => {
  try {
    await confirm(`确定要删除业务链路"${name}"吗？删除后将无法恢复。`, '确认删除')
    await deleteTrace(id)
    showSuccess('删除成功')
    loadList()
  } catch (error) {
    if (error.message !== '用户取消操作') {
      showError(error.message || '删除失败')
    }
  }
}

// 分页大小改变
const handleSizeChange = () => {
  loadList()
}

// 页码改变
const handlePageChange = () => {
  loadList()
}

// ========== 表单相关方法 ==========

// 添加顶级节点
const handleAddRootNode = () => {
  nodeDialogTitle.value = '添加顶级节点'
  currentNode.value = {
    name: '',
    description: '',
    slsLogstore: 'all',
    queryTemplate: '',
    nodeOrder: form.nodes.length,
    parentId: null
  }
  currentNodeIndex.value = -1
  isAddingChild.value = false
  nodeDialogVisible.value = true
}

// 添加子节点
const handleAddChildNode = (parentNode) => {
  const parentIndex = form.nodes.findIndex((_n, idx) => `node-${idx}` === parentNode.key)
  if (parentIndex === -1) return

  nodeDialogTitle.value = `添加子节点（父节点: ${parentNode.name}）`
  currentNode.value = {
    name: '',
    description: '',
    slsLogstore: 'all',
    queryTemplate: '',
    nodeOrder: form.nodes.length,
    parentId: parentIndex
  }
  currentNodeIndex.value = -1
  isAddingChild.value = true
  parentNodeForChild.value = parentIndex
  nodeDialogVisible.value = true
}

// 编辑节点
const handleEditNode = (node) => {
  const nodeIndex = form.nodes.findIndex((_n, idx) => `node-${idx}` === node.key)
  if (nodeIndex === -1) return

  nodeDialogTitle.value = '编辑节点'
  currentNode.value = { ...form.nodes[nodeIndex] }
  currentNodeIndex.value = nodeIndex
  isAddingChild.value = false
  nodeDialogVisible.value = true
}

// 设为子节点
const handleSetAsChild = (node) => {
  const nodeIndex = form.nodes.findIndex((_n, idx) => `node-${idx}` === node.key)
  if (nodeIndex === -1) return

  // 获取可选的父节点列表（只有顶级节点可以作为父节点，且不能是自己）
  const availableParents = form.nodes
    .map((n, idx) => ({ node: n, index: idx }))
    .filter(({ node: n, index: idx }) => n.parentId == null && idx !== nodeIndex)

  if (availableParents.length === 0) {
    ElMessageBox.alert('没有可用的父节点。请先创建其他顶级节点。', '提示', {
      confirmButtonText: '确定',
      type: 'info'
    })
    return
  }

  // 显示选择父节点对话框
  selectedNodeForParent.value = nodeIndex
  parentSelectionDialogVisible.value = true
}

// 设为顶级节点
const handleSetAsRoot = (node) => {
  const nodeIndex = form.nodes.findIndex((_n, idx) => `node-${idx}` === node.key)
  if (nodeIndex === -1) return

  form.nodes[nodeIndex].parentId = null
}

// 确认选择父节点
const handleConfirmParentSelection = () => {
  if (selectedParentIndex.value !== null && selectedNodeForParent.value !== -1) {
    form.nodes[selectedNodeForParent.value].parentId = selectedParentIndex.value
    parentSelectionDialogVisible.value = false
    selectedParentIndex.value = null
    selectedNodeForParent.value = -1
  }
}

// 关闭父节点选择对话框
const handleCloseParentSelection = () => {
  parentSelectionDialogVisible.value = false
  selectedParentIndex.value = null
  selectedNodeForParent.value = -1
}

// 删除节点
const handleDeleteNode = async (node) => {
  const nodeIndex = form.nodes.findIndex((_n, idx) => `node-${idx}` === node.key)
  if (nodeIndex === -1) return

  // 检查是否有子节点
  const hasChildren = form.nodes.some((n) => n.parentId === nodeIndex)
  
  if (hasChildren) {
    // 有子节点时不允许删除
    ElMessageBox.alert('该节点有子节点，请先删除子节点后再删除该节点', '无法删除', {
      confirmButtonText: '确定',
      type: 'warning'
    })
    return
  }

  try {
    await ElMessageBox.confirm(`确定要删除节点"${node.name}"吗？`, '确认删除', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })

    // 调整parentId索引
    form.nodes.forEach((n) => {
      if (n.parentId != null && n.parentId > nodeIndex) {
        n.parentId = n.parentId - 1
      }
    })

    form.nodes.splice(nodeIndex, 1)
    updateNodeOrder()
  } catch {
    // 用户取消
  }
}

// 保存节点
const handleSaveNode = async () => {
  if (!nodeFormRef.value) return

  await nodeFormRef.value.validate(async (valid) => {
    if (!valid) return

    if (currentNodeIndex.value === -1) {
      // 添加新节点
      form.nodes.push({ ...currentNode.value })
    } else {
      // 编辑现有节点
      form.nodes[currentNodeIndex.value] = { ...currentNode.value }
    }

    updateNodeOrder()
    nodeDialogVisible.value = false
  })
}

// 对话框关闭
const handleDialogClose = () => {
  nodeFormRef.value?.resetFields()
}

// 更新节点顺序
const updateNodeOrder = () => {
  form.nodes.forEach((node, idx) => {
    node.nodeOrder = idx
  })
}

// 控制节点拖拽放置规则
const handleAllowDrop = (draggingNode, dropNode, type) => {
  const draggingData = draggingNode.data
  const dropData = dropNode.data

  // 如果是放置到节点内部（成为子节点）
  if (type === 'inner') {
    // 只有顶级节点可以接收子节点（因为只支持一级子节点）
    if (dropData.parentId != null) {
      return false
    }
    // 只允许将没有子节点的顶级节点改为子节点
    if (draggingData.parentId == null && draggingData.children && draggingData.children.length > 0) {
      return false
    }
    return true
  }

  // 如果是放置到节点前后（同级）
  // 只能在同一层级拖动（都是顶级节点或都有相同的父节点）
  if (draggingData.parentId === dropData.parentId) {
    return true
  }

  return false
}

// 处理节点拖拽完成
const handleNodeDrop = () => {
  // 从树形结构重建nodes数组
  const newNodes = []
  const nodeIndexMap = new Map()

  const traverseTree = (nodes, parentIndex = null) => {
    nodes.forEach((node) => {
      const currentIndex = newNodes.length
      nodeIndexMap.set(node.key, currentIndex)

      // 创建新节点
      const newNode = {
        id: node.id,
        name: node.name,
        description: node.description,
        slsLogstore: node.slsLogstore,
        queryTemplate: node.queryTemplate,
        nodeOrder: currentIndex,
        parentId: parentIndex
      }
      newNodes.push(newNode)

      // 递归处理子节点
      if (node.children && node.children.length > 0) {
        traverseTree(node.children, currentIndex)
      }
    })
  }

  // 遍历树形结构构建新的nodes数组
  traverseTree(treeData.value)

  // 更新form.nodes
  form.nodes = newNodes
}

// 提交
const handleSubmit = async () => {
  if (!formRef.value) return

  await formRef.value.validate(async (valid) => {
    if (!valid) return

    submitting.value = true
    try {
      if (isEdit.value && traceId.value) {
        await updateTrace(traceId.value, { ...form, id: traceId.value })
        showSuccess('更新成功')
      } else {
        await createTrace(form)
        showSuccess('创建成功')
      }
      showForm.value = false
      loadList()
    } catch (error) {
      showError(error.message || '保存失败')
    } finally {
      submitting.value = false
    }
  })
}

// 取消
const handleCancel = () => {
  showForm.value = false
}

// 加载详情（编辑模式）
const loadDetail = async (id) => {
  try {
    const res = await getTraceDetail(id, true) // 传递true以获取所有节点（包括子节点）
    const detail = res.data
    form.name = detail.name
    form.description = detail.description || ''
    form.category = detail.category
    
    // 构建节点ID到索引的映射
    const nodeIdToIndexMap = new Map()
    detail.nodes.forEach((node, index) => {
      if (node.id) {
        nodeIdToIndexMap.set(node.id, index)
      }
    })
    
    // 转换节点数据，将parentId从数据库ID转换为数组索引
    form.nodes = detail.nodes.map((node) => ({
      id: node.id,
      name: node.name,
      description: node.description || '',
      slsLogstore: node.slsLogstore,
      queryTemplate: node.queryTemplate,
      nodeOrder: node.nodeOrder,
      // 将数据库的parentId转换为数组索引
      parentId: node.parentId ? nodeIdToIndexMap.get(node.parentId) ?? null : null
    }))
  } catch (error) {
    showError(error.message || '加载失败')
    showForm.value = false
  }
}

// ========== 分类相关方法 ==========

// 新建分类
const handleCreateCategory = () => {
  categoryDialogVisible.value = true
}

// 提交分类
const handleCategorySubmit = async () => {
  if (!categoryFormRef.value) return

  await categoryFormRef.value.validate(async (valid) => {
    if (!valid) return

    categorySubmitting.value = true
    try {
      await createCategory(categoryForm.value.value)
      showSuccess('创建成功')
      categoryDialogVisible.value = false
      // 重新加载分类列表
      await loadCategories()
    } catch (error) {
      showError(error.message || '创建失败')
    } finally {
      categorySubmitting.value = false
    }
  })
}

// 关闭分类对话框
const handleCategoryDialogClose = () => {
  categoryFormRef.value?.resetFields()
  categoryForm.value = {
    value: ''
  }
}

// 初始化
onMounted(() => {
  loadCategories()
  loadList()
  
  // 检查路由参数，判断是创建还是编辑
  const id = route.params.id
  if (id === 'create') {
    handleCreate()
  } else if (id && id !== 'traces') {
    handleEdit(parseInt(id))
  }
})
</script>

<style scoped>
.trace-management {
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

.header-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-title {
  font-size: 16px;
  font-weight: 500;
  color: #333;
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

.form-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: white;
  border-radius: 4px;
  padding: 16px 24px;
  overflow-y: auto;
}

.nodes-container {
  width: 100%;
}

.tree-toolbar {
  display: flex;
  align-items: center;
  gap: 15px;
  margin-bottom: 15px;
}

.empty-nodes {
  margin-top: 20px;
}

.node-tree {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 10px;
  background-color: #fafafa;
}

.tree-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: 1;
  padding: 8px 12px;
  border-radius: 4px;
  transition: background-color 0.3s;
}

.tree-node:hover {
  background-color: #f5f7fa;
}

.node-info {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
}

.drag-handle {
  color: #909399;
  font-size: 14px;
  cursor: move;
  margin-right: 4px;
}

.drag-handle:hover {
  color: #606266;
}

.node-icon {
  color: #409eff;
  font-size: 16px;
}

.node-name {
  font-weight: 500;
  color: #303133;
}

.node-desc {
  color: #909399;
  font-size: 12px;
  margin-left: 8px;
}

.node-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  opacity: 0;
  transition: opacity 0.3s;
}

.tree-node:hover .node-actions {
  opacity: 1;
}

.template-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 5px;
}

.form-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 5px;
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
