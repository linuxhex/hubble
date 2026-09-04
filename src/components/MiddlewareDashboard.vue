<template>
  <div class="middleware-page">
    <div class="page-header">
      <h2>中间件监控</h2>
      <div class="header-actions">
        <el-radio-group v-model="activeTab" size="small" @change="fetchData">
          <el-radio-button value="redis">Redis</el-radio-button>
          <el-radio-button value="mysql">MySQL / PolarDB</el-radio-button>
          <el-radio-button value="pod">Pod</el-radio-button>
          <el-radio-button value="node">Node</el-radio-button>
        </el-radio-group>
        <el-button size="small" @click="fetchData" :loading="loading">刷新</el-button>
      </div>
    </div>

    <!-- Redis 监控 -->
    <div v-if="activeTab === 'redis'" class="monitor-section">
      <el-table v-loading="loading" :data="redisData" stripe border size="small" style="width: 100%">
        <el-table-column label="实例ID" width="200" show-overflow-tooltip prop="instanceId" />
        <el-table-column label="实例名称" width="180" show-overflow-tooltip prop="instanceName" />
        <el-table-column label="规格" width="140" prop="instanceType" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'Normal' ? 'success' : 'warning'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="CPU%" width="100" align="right">
          <template #default="{ row }">
            <span :class="metricClass(row.cpuUsage)">{{ row.cpuUsage.toFixed(1) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="连接数" width="100" align="right">
          <template #default="{ row }">{{ Math.round(row.connections) }}</template>
        </el-table-column>
        <el-table-column label="内存%" width="100" align="right">
          <template #default="{ row }">
            <span :class="metricClass(row.memoryUsage)">{{ row.memoryUsage.toFixed(1) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="QPS" width="100" align="right">
          <template #default="{ row }">{{ Math.round(row.qps) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && redisData.length === 0" description="暂无 Redis 实例" />
    </div>

    <!-- MySQL/PolarDB 监控 -->
    <div v-if="activeTab === 'mysql'" class="monitor-section">
      <el-table v-loading="loading" :data="mysqlData" stripe border size="small" style="width: 100%">
        <el-table-column label="实例ID" width="200" show-overflow-tooltip prop="instanceId" />
        <el-table-column label="实例名称" width="180" show-overflow-tooltip prop="instanceName" />
        <el-table-column label="引擎" width="100" prop="engine" />
        <el-table-column label="版本" width="100" prop="engineVersion" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'Running' ? 'success' : 'warning'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="CPU%" width="100" align="right">
          <template #default="{ row }">
            <span :class="metricClass(row.cpuUsage)">{{ row.cpuUsage.toFixed(1) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="连接数" width="100" align="right">
          <template #default="{ row }">{{ Math.round(row.connections) }}</template>
        </el-table-column>
        <el-table-column label="IOPS" width="100" align="right">
          <template #default="{ row }">{{ Math.round(row.iops) }}</template>
        </el-table-column>
        <el-table-column label="内存%" width="100" align="right">
          <template #default="{ row }">
            <span :class="metricClass(row.memoryUsage)">{{ row.memoryUsage.toFixed(1) }}%</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && mysqlData.length === 0" description="暂无 MySQL/PolarDB 实例" />
    </div>

    <!-- Pod 监控 -->
    <div v-if="activeTab === 'pod'" class="monitor-section">
      <div class="pod-grid">
        <div class="pod-card">
          <div class="card-title">Pod CPU Top10</div>
          <el-table v-loading="loading" :data="podCpuData" stripe size="small" style="width: 100%">
            <el-table-column label="排名" width="60" type="index" />
            <el-table-column label="Pod" min-width="250" show-overflow-tooltip prop="pod" />
            <el-table-column label="CPU" width="100" align="right">
              <template #default="{ row }">{{ Number(row.cpu).toFixed(3) }}</template>
            </el-table-column>
          </el-table>
        </div>
        <div class="pod-card">
          <div class="card-title">Pod 内存 Top10</div>
          <el-table v-loading="loading" :data="podMemData" stripe size="small" style="width: 100%">
            <el-table-column label="排名" width="60" type="index" />
            <el-table-column label="Pod" min-width="250" show-overflow-tooltip prop="pod" />
            <el-table-column label="内存(MB)" width="100" align="right">
              <template #default="{ row }">{{ row.memoryMB }}</template>
            </el-table-column>
          </el-table>
        </div>
      </div>
      <el-empty v-if="!loading && podCpuData.length === 0 && podMemData.length === 0" description="暂无 Pod 数据" />
    </div>

    <!-- Node 监控 -->
    <div v-if="activeTab === 'node'" class="monitor-section">
      <el-table v-loading="loading" :data="nodeData" stripe border size="small" style="width: 100%">
        <el-table-column label="排名" width="60" type="index" />
        <el-table-column label="节点" min-width="300" show-overflow-tooltip prop="node" />
        <el-table-column label="CPU%" width="120" align="right">
          <template #default="{ row }">
            <span :class="metricClass(row.cpuUsage)">{{ row.cpuUsage.toFixed(1) }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="内存%" width="120" align="right">
          <template #default="{ row }">
            <span :class="metricClass(row.memoryUsage)">{{ row.memoryUsage.toFixed(1) }}%</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && nodeData.length === 0" description="暂无 Node 数据" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getRedisInstances, getMysqlInstances, getPodCpuTop, getPodMemoryTop, getNodeOverview } from '@/api/middleware.js'

const loading = ref(false)
const activeTab = ref('redis')
const redisData = ref([])
const mysqlData = ref([])
const podCpuData = ref([])
const podMemData = ref([])
const nodeData = ref([])

const metricClass = (val) => {
  if (val > 80) return 'metric-critical'
  if (val > 50) return 'metric-warning'
  return 'metric-normal'
}

const fetchData = async () => {
  loading.value = true
  try {
    if (activeTab.value === 'redis') {
      const res = await getRedisInstances()
      redisData.value = res.data || []
    } else if (activeTab.value === 'mysql') {
      const res = await getMysqlInstances()
      mysqlData.value = res.data || []
    } else if (activeTab.value === 'pod') {
      const [cpuRes, memRes] = await Promise.all([getPodCpuTop(), getPodMemoryTop()])
      podCpuData.value = cpuRes.data || []
      podMemData.value = memRes.data || []
    } else if (activeTab.value === 'node') {
      const res = await getNodeOverview()
      nodeData.value = res.data || []
    }
  } catch (error) {
    console.error('Fetch error:', error)
  } finally {
    loading.value = false
  }
}

onMounted(() => fetchData())
</script>

<style scoped>
.middleware-page { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { margin: 0; font-size: 20px; }
.header-actions { display: flex; align-items: center; gap: 12px; }
.monitor-section { margin-bottom: 20px; }
.pod-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.pod-card { border: 1px solid #ebeef5; border-radius: 8px; padding: 12px; }
.card-title { font-size: 14px; font-weight: 600; margin-bottom: 8px; }
.metric-critical { color: #f56c6c; font-weight: 700; }
.metric-warning { color: #e6a23c; font-weight: 600; }
.metric-normal { color: #67c23a; }
</style>
