<template>
  <div class="middleware-page">
    <div class="page-header">
      <h2>中间件监控</h2>
      <div class="header-actions">
        <el-radio-group v-model="activeTab" size="small" @change="fetchData">
          <el-radio-button value="redis">Redis</el-radio-button>
          <el-radio-button value="mysql">MySQL / PolarDB</el-radio-button>
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
            <el-tag :type="row.status === 'Normal' ? 'success' : 'warning'" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="CPU%" width="100" align="right">
          <template #default="{ row }">
            <span :class="row.cpuUsage > 80 ? 'metric-critical' : row.cpuUsage > 50 ? 'metric-warning' : 'metric-normal'">
              {{ row.cpuUsage.toFixed(1) }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column label="连接数" width="100" align="right">
          <template #default="{ row }">{{ Math.round(row.connections) }}</template>
        </el-table-column>
        <el-table-column label="内存%" width="100" align="right">
          <template #default="{ row }">
            <span :class="row.memoryUsage > 80 ? 'metric-critical' : row.memoryUsage > 50 ? 'metric-warning' : 'metric-normal'">
              {{ row.memoryUsage.toFixed(1) }}%
            </span>
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
            <el-tag :type="row.status === 'Running' ? 'success' : 'warning'" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="CPU%" width="100" align="right">
          <template #default="{ row }">
            <span :class="row.cpuUsage > 80 ? 'metric-critical' : row.cpuUsage > 50 ? 'metric-warning' : 'metric-normal'">
              {{ row.cpuUsage.toFixed(1) }}%
            </span>
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
            <span :class="row.memoryUsage > 80 ? 'metric-critical' : row.memoryUsage > 50 ? 'metric-warning' : 'metric-normal'">
              {{ row.memoryUsage.toFixed(1) }}%
            </span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && mysqlData.length === 0" description="暂无 MySQL/PolarDB 实例" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getRedisInstances, getMysqlInstances } from '@/api/middleware.js'

const loading = ref(false)
const activeTab = ref('redis')
const redisData = ref([])
const mysqlData = ref([])

const fetchData = async () => {
  loading.value = true
  try {
    if (activeTab.value === 'redis') {
      const res = await getRedisInstances()
      redisData.value = res.data || []
    } else {
      const res = await getMysqlInstances()
      mysqlData.value = res.data || []
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
.middleware-page {
  padding: 16px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.monitor-section {
  margin-bottom: 20px;
}

.metric-critical { color: #f56c6c; font-weight: 700; }
.metric-warning { color: #e6a23c; font-weight: 600; }
.metric-normal { color: #67c23a; }
</style>
