<template>
  <div class="alert-config-management">
    <el-tabs v-model="activeTab" class="config-tabs">
      <!-- Tab1: 日志告警规则 -->
      <el-tab-pane label="日志告警规则" name="rules">
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
                {{ row.startTime }} ~ {{ row.endTime }}
              </template>
            </el-table-column>
            <el-table-column prop="collectionIntervalDisplay" label="采集间隔" min-width="100" />
            <el-table-column prop="alertThreshold" label="告警阈值" min-width="100" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-switch
                  v-model="row.enabled"
                  @change="handleToggleEnabled(row)"
                  :disabled="!isLoggedIn"
                />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="260" fixed="right">
              <template #default="{ row }">
                <span class="action-link edit-link" @click="handleEdit(row)">编辑</span>
                <span class="action-link delete-link" @click="handleDelete(row.id, row.title)">删除</span>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination">
            <el-pagination
              v-model:current-page="pagination.current"
              v-model:page-size="pagination.size"
              :total="pagination.total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              @size-change="handleSizeChange"
              @current-change="handleCurrentChange"
            />
          </div>
        </el-card>

        <!-- 新建/编辑弹窗 -->
        <el-dialog
          v-model="dialogVisible"
          :title="isEdit ? '编辑监控配置' : '新建监控配置'"
          width="600px"
          @close="handleDialogClose"
        >
          <el-form ref="formRef" :model="form" :rules="formRules" label-width="120px">
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
                filterable
                remote
                reserve-keyword
                placeholder="请输入关键词搜索SLS模板"
                :remote-method="handleSearchSlsKeyword"
                :loading="slsKeywordLoading"
                style="width: 100%"
                @visible-change="handleSlsKeywordVisibleChange"
              >
                <el-option
                  v-for="item in slsKeywordList"
                  :key="item.id"
                  :value="item.id"
                  :label="`【${item.application || ''}】${item.desc}`"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="监控时间" required>
              <div class="time-range-group">
                <el-time-select
                  v-model="startTimeValue"
                  :max-time="endTimeValue"
                  placeholder="开始时间"
                  start="00:00"
                  step="00:30"
                  end="23:59"
                />
                <span class="time-separator">~</span>
                <el-time-select
                  v-model="endTimeValue"
                  :min-time="startTimeValue"
                  placeholder="结束时间"
                  start="00:00"
                  step="00:30"
                  end="23:59"
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
            <el-form-item label="通知机器人">
              <el-select
                v-model="form.robotIds"
                multiple
                filterable
                placeholder="选择通知到哪些钉钉群（可多选）"
                style="width: 100%"
              >
                <el-option
                  v-for="r in enabledRobots"
                  :key="r.id"
                  :value="r.id"
                  :label="r.name + (r.remark ? '（' + r.remark + '）' : '')"
                />
              </el-select>
            </el-form-item>
          </el-form>

          <template #footer>
            <el-button @click="dialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- Tab2: 钉钉机器人管理 -->
      <el-tab-pane label="钉钉机器人" name="robots">
        <el-card body-style="padding: 16px">
          <div class="tab-header">
            <el-alert
              title="配置多个钉钉群机器人，告警规则可多选通知到哪些群"
              type="info"
              :closable="false"
              show-icon
              style="margin-bottom: 16px"
            />
            <el-button type="success" @click="handleRobotCreate">
              <el-icon><Plus /></el-icon>
              新增机器人
            </el-button>
          </div>

          <el-table v-loading="robotLoading" :data="robotList" stripe style="width: 100%">
            <el-table-column prop="name" label="名称" min-width="140" />
            <el-table-column label="Webhook" min-width="240">
              <template #default="{ row }">
                <span class="webhook-text">{{ maskWebhook(row.webhook) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" :active-value="1" :inactive-value="0" @change="handleRobotToggle(row)" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="240" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="handleRobotTest(row.id)">测试发送</el-button>
                <el-button type="primary" link size="small" @click="handleRobotEdit(row)">编辑</el-button>
                <el-button type="danger" link size="small" @click="handleRobotDelete(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- 机器人新增/编辑弹窗 -->
        <el-dialog
          v-model="robotDialogVisible"
          :title="robotIsEdit ? '编辑机器人' : '新增机器人'"
          width="520px"
        >
          <el-form ref="robotFormRef" :model="robotForm" :rules="robotRules" label-width="100px">
            <el-form-item label="名称" prop="name">
              <el-input v-model="robotForm.name" placeholder="如：运维群机器人" maxlength="100" />
            </el-form-item>
            <el-form-item label="Webhook" prop="webhook">
              <el-input
                v-model="robotForm.webhook"
                type="textarea"
                :rows="2"
                placeholder="钉钉机器人 Webhook 地址（https://oapi.dingtalk.com/robot/send?access_token=xxx）"
              />
            </el-form-item>
            <el-form-item label="加签密钥" prop="secret">
              <el-input v-model="robotForm.secret" placeholder="加签密钥（选填，SEC开头的字符串）" />
            </el-form-item>
            <el-form-item label="备注" prop="remark">
              <el-input v-model="robotForm.remark" placeholder="如：通知到运维群" maxlength="200" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="robotDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="robotSubmitting" @click="handleRobotSubmit">确定</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- Tab3: 服务告警阈值 -->
      <el-tab-pane label="服务告警阈值" name="thresholds">
        <el-card body-style="padding: 16px">
          <el-alert
            title="统一配置接口劣化、流量暴涨、红黄盘等告警阈值，修改后即时生效"
            type="info"
            :closable="false"
            show-icon
            style="margin-bottom: 20px"
          />
          <el-table v-loading="thresholdLoading" :data="thresholdList" stripe style="width: 100%">
            <el-table-column prop="description" label="阈值说明" min-width="240" />
            <el-table-column prop="configKey" label="配置键" min-width="200" show-overflow-tooltip />
            <el-table-column label="当前值" width="180">
              <template #default="{ row }">
                <el-input-number v-model="row.configValue" :controls="false" style="width: 140px" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="handleThresholdSave(row)">保存</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- Tab4: 中间件告警阈值 -->
      <el-tab-pane label="中间件告警阈值" name="middleware">
        <el-card body-style="padding: 16px">
          <div class="tab-header">
            <el-alert
              title="配置中间件监控的红盘/黄盘阈值，支持按实例或全局默认配置"
              type="info"
              :closable="false"
              show-icon
              style="margin-bottom: 16px"
            />
            <el-form :inline="true" class="search-form">
              <el-form-item label="中间件类型">
                <el-select v-model="mwFilterType" placeholder="全部" clearable style="width: 160px" @change="loadMwAlertList">
                  <el-option label="Redis" value="redis" />
                  <el-option label="MySQL" value="mysql" />
                  <el-option label="RocketMQ" value="rocketmq" />
                  <el-option label="Kafka" value="kafka" />
                  <el-option label="Lindorm" value="lindorm" />
                  <el-option label="Elasticsearch" value="elasticsearch" />
                  <el-option label="OSS" value="oss" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="isLoggedIn">
                <el-button type="success" @click="handleMwCreate">
                  <el-icon><Plus /></el-icon>
                  新增配置
                </el-button>
              </el-form-item>
            </el-form>
          </div>

          <el-table v-loading="mwLoading" :data="mwConfigList" stripe style="width: 100%">
            <el-table-column label="中间件类型" width="140">
              <template #default="{ row }">
                <el-tag :type="getMwTypeTagType(row.middlewareType)" size="small">
                  {{ getMwTypeLabel(row.middlewareType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="instanceId" label="实例ID" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.instanceId || '全局默认' }}
              </template>
            </el-table-column>
            <el-table-column prop="metricName" label="监控指标" min-width="140" />
            <el-table-column label="黄盘阈值" width="120">
              <template #default="{ row }">
                {{ row.yellowThreshold }}
              </template>
            </el-table-column>
            <el-table-column label="红盘阈值" width="120">
              <template #default="{ row }">
                {{ row.redThreshold }}
              </template>
            </el-table-column>
            <el-table-column prop="compareType" label="比较方式" width="100" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-switch
                  v-model="row.enabled"
                  :active-value="true"
                  :inactive-value="false"
                  @change="handleMwToggle(row)"
                  :disabled="!isLoggedIn"
                />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <span class="action-link edit-link" @click="handleMwEdit(row)">编辑</span>
                <span class="action-link delete-link" @click="handleMwDelete(row)">删除</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- 中间件告警配置弹窗 -->
        <el-dialog
          v-model="mwDialogVisible"
          :title="mwIsEdit ? '编辑中间件告警配置' : '新增中间件告警配置'"
          width="560px"
        >
          <el-form ref="mwFormRef" :model="mwForm" :rules="mwFormRules" label-width="110px">
            <el-form-item label="中间件类型" prop="middlewareType">
              <el-select v-model="mwForm.middlewareType" placeholder="请选择" style="width: 100%">
                <el-option label="Redis" value="redis" />
                <el-option label="MySQL" value="mysql" />
                <el-option label="RocketMQ" value="rocketmq" />
                <el-option label="Kafka" value="kafka" />
                <el-option label="Lindorm" value="lindorm" />
                <el-option label="Elasticsearch" value="elasticsearch" />
                <el-option label="OSS" value="oss" />
              </el-select>
            </el-form-item>
            <el-form-item label="实例ID" prop="instanceId">
              <el-input v-model="mwForm.instanceId" placeholder="留空表示全局默认" />
            </el-form-item>
            <el-form-item label="监控指标" prop="metricName">
              <el-input v-model="mwForm.metricName" placeholder="如: cpuUsage, memoryUsage, diskUsage" />
            </el-form-item>
            <el-form-item label="黄盘阈值" prop="yellowThreshold">
              <el-input-number v-model="mwForm.yellowThreshold" :min="0" :precision="2" style="width: 100%" />
            </el-form-item>
            <el-form-item label="红盘阈值" prop="redThreshold">
              <el-input-number v-model="mwForm.redThreshold" :min="0" :precision="2" style="width: 100%" />
            </el-form-item>
            <el-form-item label="比较方式" prop="compareType">
              <el-select v-model="mwForm.compareType" style="width: 100%">
                <el-option label="大于 (>)" value=">" />
                <el-option label="大于等于 (>=)" value=">=" />
                <el-option label="小于 (<)" value="<" />
                <el-option label="小于等于 (<=)" value="<=" />
              </el-select>
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="mwDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="mwSubmitting" @click="handleMwSubmit">确定</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
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
import {
  getRobotList, getEnabledRobots, createRobot, updateRobot, deleteRobot,
  enableRobot, disableRobot, testSendRobot
} from '@/api/dingtalk-robot.js'
import { getThresholdList, updateThreshold } from '@/api/alert-threshold.js'
import {
  getAlertConfigs, createMiddlewareAlertConfig,
  updateMiddlewareAlertConfig, deleteMiddlewareAlertConfig
} from '@/api/middleware.js'
import { useAuthStore } from '@/stores/auth.js'

const authStore = useAuthStore()
const isLoggedIn = computed(() => authStore.isLoggedIn)

// ===== Tab 切换 =====
const activeTab = ref('rules')

// ===== Tab1: 日志告警规则 =====
const loading = ref(false)
const configList = ref([])
const slsKeywordList = ref([])
const slsKeywordLoading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref(null)
const enabledRobots = ref([])

const searchForm = reactive({ title: '', enabled: undefined })
const slsKeywordPagination = reactive({ current: 1, size: 20, total: 0 })
const pagination = reactive({ current: 1, size: 10, total: 0 })

const form = reactive({
  title: '', description: '', keywordTemplateId: '',
  startTime: '00:00:00', endTime: '23:59:59',
  collectionInterval: 60, alertThreshold: 50,
  alertWebhook: '', robotIds: []
})

const startTimeValue = computed({ get: () => form.startTime, set: (val) => { form.startTime = val } })
const endTimeValue = computed({ get: () => form.endTime, set: (val) => { form.endTime = val } })

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
    const res = await getAlertConfigList({ ...searchForm, current: pagination.current, size: pagination.size })
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
    const res = await getSlsKeywordList({ current: slsKeywordPagination.current, size: slsKeywordPagination.size })
    if (res.code === 200) {
      const list = res.data.records || res.data.list || []
      slsKeywordList.value = append ? [...slsKeywordList.value, ...list] : list
      slsKeywordPagination.total = res.data.total || 0
    }
  } catch (e) {
    console.error('加载SLS模板列表失败:', e)
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
    const res = await getSlsKeywordList({ current: 1, size: 100, desc: query })
    if (res.code === 200) {
      slsKeywordList.value = res.data.records || res.data.list || []
      slsKeywordPagination.total = slsKeywordList.value.length
    }
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

const handleSearch = () => { pagination.current = 1; loadConfigList() }
const handleReset = () => { searchForm.title = ''; searchForm.enabled = undefined; pagination.current = 1; loadConfigList() }

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
      // 确保 robotIds 是数组
      form.robotIds = res.data.robotIds || []
      dialogVisible.value = true
    }
  } catch (e) {
    ElMessage.error('加载配置详情失败')
  }
}

const handleDelete = (id, title) => {
  ElMessageBox.confirm(`确定要删除监控配置"${title}"吗？`, '提示', {
    confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning'
  }).then(async () => {
    try {
      await deleteAlertConfig(id)
      ElMessage.success('删除成功')
      loadConfigList()
    } catch (e) {
      ElMessage.error('删除失败')
    }
  })
}

const handleToggleEnabled = async (row) => {
  try {
    if (row.enabled) { await enableAlertConfig(row.id); ElMessage.success('已启用') }
    else { await disableAlertConfig(row.id); ElMessage.success('已禁用') }
    loadConfigList()
  } catch (e) {
    row.enabled = !row.enabled
    ElMessage.error('操作失败')
  }
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
        await createAlertConfig(form)
        ElMessage.success('创建成功')
      }
      dialogVisible.value = false
      loadConfigList()
    } catch (e) {
      ElMessage.error('操作失败')
    } finally {
      submitting.value = false
    }
  })
}

const handleDialogClose = () => { resetForm() }

const resetForm = () => {
  formRef.value?.resetFields()
  Object.assign(form, {
    title: '', description: '', keywordTemplateId: '',
    startTime: '00:00:00', endTime: '23:59:59',
    collectionInterval: 60, alertThreshold: 50,
    alertWebhook: '', robotIds: []
  })
}

const handleSizeChange = () => { pagination.current = 1; loadConfigList() }
const handleCurrentChange = () => { loadConfigList() }

// ===== Tab2: 钉钉机器人 =====
const robotLoading = ref(false)
const robotList = ref([])
const robotDialogVisible = ref(false)
const robotIsEdit = ref(false)
const robotSubmitting = ref(false)
const robotFormRef = ref(null)
const robotForm = reactive({ name: '', webhook: '', secret: '', remark: '', enabled: 1 })
const robotRules = {
  name: [{ required: true, message: '请输入机器人名称', trigger: 'blur' }],
  webhook: [{ required: true, message: '请输入 Webhook 地址', trigger: 'blur' }]
}

const loadRobots = async () => {
  robotLoading.value = true
  try {
    const res = await getRobotList()
    robotList.value = res.data || []
  } finally {
    robotLoading.value = false
  }
}

const loadEnabledRobots = async () => {
  try {
    const res = await getEnabledRobots()
    enabledRobots.value = res.data || []
  } catch (e) {
    console.error('加载启用机器人失败', e)
  }
}

const maskWebhook = (url) => {
  if (!url) return '--'
  // 只显示前 40 字符 + ***
  return url.length > 40 ? url.substring(0, 40) + '***' : url
}

const handleRobotCreate = () => {
  robotIsEdit.value = false
  Object.assign(robotForm, { name: '', webhook: '', secret: '', remark: '', enabled: 1 })
  robotDialogVisible.value = true
}

const handleRobotEdit = (row) => {
  robotIsEdit.value = true
  Object.assign(robotForm, row)
  robotDialogVisible.value = true
}

const handleRobotSubmit = async () => {
  if (!robotFormRef.value) return
  await robotFormRef.value.validate(async (valid) => {
    if (!valid) return
    robotSubmitting.value = true
    try {
      if (robotIsEdit.value) {
        await updateRobot(robotForm.id, robotForm)
        ElMessage.success('更新成功')
      } else {
        await createRobot(robotForm)
        ElMessage.success('创建成功')
      }
      robotDialogVisible.value = false
      loadRobots()
      loadEnabledRobots()
    } catch (e) {
      ElMessage.error('操作失败')
    } finally {
      robotSubmitting.value = false
    }
  })
}

const handleRobotDelete = (row) => {
  ElMessageBox.confirm(`确定要删除机器人"${row.name}"吗？关联的告警规则将不再通知到该群`, '提示', {
    confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning'
  }).then(async () => {
    try {
      await deleteRobot(row.id)
      ElMessage.success('删除成功')
      loadRobots()
      loadEnabledRobots()
    } catch (e) {
      ElMessage.error('删除失败')
    }
  })
}

const handleRobotToggle = async (row) => {
  try {
    if (row.enabled === 1) { await enableRobot(row.id); ElMessage.success('已启用') }
    else { await disableRobot(row.id); ElMessage.success('已禁用') }
    loadEnabledRobots()
  } catch (e) {
    row.enabled = row.enabled === 1 ? 0 : 1
    ElMessage.error('操作失败')
  }
}

const handleRobotTest = async (id) => {
  try {
    await testSendRobot(id)
    ElMessage.success('测试消息已发送，请到钉钉群查看')
  } catch (e) {
    ElMessage.error('测试发送失败：' + (e.message || '未知错误'))
  }
}

// ===== Tab3: 服务告警阈值 =====
const thresholdLoading = ref(false)
const thresholdList = ref([])

const loadThresholds = async () => {
  thresholdLoading.value = true
  try {
    const res = await getThresholdList()
    thresholdList.value = res.data || []
  } finally {
    thresholdLoading.value = false
  }
}

const handleThresholdSave = async (row) => {
  try {
    await updateThreshold(row.configKey, row.configValue)
    ElMessage.success(row.description + ' 已更新为 ' + row.configValue)
  } catch (e) {
    ElMessage.error('保存失败')
  }
}

// ===== Tab4: 中间件告警阈值 =====
const mwLoading = ref(false)
const mwConfigList = ref([])
const mwFilterType = ref('')
const mwDialogVisible = ref(false)
const mwIsEdit = ref(false)
const mwSubmitting = ref(false)
const mwFormRef = ref(null)

const mwForm = reactive({
  id: null,
  middlewareType: '',
  instanceId: '',
  metricName: '',
  yellowThreshold: 0,
  redThreshold: 0,
  compareType: '>',
  enabled: true
})

const mwFormRules = {
  middlewareType: [{ required: true, message: '请选择中间件类型', trigger: 'change' }],
  metricName: [{ required: true, message: '请输入监控指标', trigger: 'blur' }],
  yellowThreshold: [{ required: true, message: '请输入黄盘阈值', trigger: 'blur' }],
  redThreshold: [{ required: true, message: '请输入红盘阈值', trigger: 'blur' }],
  compareType: [{ required: true, message: '请选择比较方式', trigger: 'change' }]
}

const mwTypeMap = {
  redis: 'Redis',
  mysql: 'MySQL',
  rocketmq: 'RocketMQ',
  kafka: 'Kafka',
  lindorm: 'Lindorm',
  elasticsearch: 'Elasticsearch',
  oss: 'OSS'
}

const getMwTypeLabel = (type) => mwTypeMap[type] || type
const getMwTypeTagType = (type) => {
  const map = { redis: 'danger', mysql: 'warning', rocketmq: 'success', kafka: 'success', lindorm: 'info', elasticsearch: 'info', oss: '' }
  return map[type] || ''
}

const loadMwAlertList = async () => {
  mwLoading.value = true
  try {
    const res = await getAlertConfigs(mwFilterType.value || undefined)
    mwConfigList.value = res.data || []
  } finally {
    mwLoading.value = false
  }
}

const handleMwCreate = () => {
  mwIsEdit.value = false
  Object.assign(mwForm, {
    id: null, middlewareType: '', instanceId: '', metricName: '',
    yellowThreshold: 0, redThreshold: 0, compareType: '>', enabled: true
  })
  mwDialogVisible.value = true
}

const handleMwEdit = (row) => {
  mwIsEdit.value = true
  Object.assign(mwForm, {
    id: row.id,
    middlewareType: row.middlewareType,
    instanceId: row.instanceId || '',
    metricName: row.metricName,
    yellowThreshold: Number(row.yellowThreshold),
    redThreshold: Number(row.redThreshold),
    compareType: row.compareType,
    enabled: row.enabled
  })
  mwDialogVisible.value = true
}

const handleMwSubmit = async () => {
  if (!mwFormRef.value) return
  await mwFormRef.value.validate(async (valid) => {
    if (!valid) return
    mwSubmitting.value = true
    try {
      const payload = { ...mwForm }
      if (!payload.instanceId) payload.instanceId = null
      if (mwIsEdit.value) {
        await updateMiddlewareAlertConfig(payload.id, payload)
        ElMessage.success('更新成功')
      } else {
        await createMiddlewareAlertConfig(payload)
        ElMessage.success('创建成功')
      }
      mwDialogVisible.value = false
      loadMwAlertList()
    } catch (e) {
      ElMessage.error('操作失败')
    } finally {
      mwSubmitting.value = false
    }
  })
}

const handleMwDelete = (row) => {
  const label = mwTypeMap[row.middlewareType] || row.middlewareType
  ElMessageBox.confirm(`确定要删除${label}的"${row.metricName}"告警配置吗？`, '提示', {
    confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning'
  }).then(async () => {
    try {
      await deleteMiddlewareAlertConfig(row.id)
      ElMessage.success('删除成功')
      loadMwAlertList()
    } catch (e) {
      ElMessage.error('删除失败')
    }
  })
}

const handleMwToggle = async (row) => {
  try {
    const payload = {
      middlewareType: row.middlewareType,
      instanceId: row.instanceId,
      metricName: row.metricName,
      yellowThreshold: row.yellowThreshold,
      redThreshold: row.redThreshold,
      compareType: row.compareType,
      enabled: row.enabled
    }
    await updateMiddlewareAlertConfig(row.id, payload)
    ElMessage.success(row.enabled ? '已启用' : '已禁用')
  } catch (e) {
    row.enabled = !row.enabled
    ElMessage.error('操作失败')
  }
}

// ===== 初始化 =====
onMounted(() => {
  loadConfigList()
  loadSlsKeywordList()
  loadEnabledRobots()
  loadRobots()
  loadThresholds()
  loadMwAlertList()
})
</script>

<style scoped>
.alert-config-management {
  padding: 0;
}

.config-tabs {
  padding: 0 16px;
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

.edit-link { color: #409eff; }
.edit-link:hover { color: #2563eb; text-decoration: underline; }
.delete-link { color: #f56c6c; }
.delete-link:hover { color: #e11d48; text-decoration: underline; }

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

.tab-header {
  margin-bottom: 16px;
}

.webhook-text {
  font-size: 12px;
  color: #909399;
  word-break: break-all;
}
</style>
