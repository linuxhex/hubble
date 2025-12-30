<template>
  <div class="monitor-dashboard">
    <!-- 过滤器 -->
    <div class="dashboard-header">
      <div class="dashboard-title">
      </div>
      <div class="filter-area">
        <el-dropdown @command="handleCommand">
          <span class="time-filter">
            监控大盘 <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="/monitor">监控大盘</el-dropdown-item>
              <el-dropdown-item command="/abnormal">异常大盘</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <!-- 服务概览卡片 -->
    <div class="service-overview">
      <div class="section-title">服务概览</div>
      <div class="card-container">
        <div class="overview-card">
          <div class="card-title">总服务数</div>
          <div class="card-value">128</div>
          <div class="card-footer">
            <span class="trend-up">↑ 12.5%</span>
            <span>较上周</span>
          </div>
        </div>
        <div class="overview-card">
          <div class="card-title">异常服务</div>
          <div class="card-value">16</div>
          <div class="card-footer">
            <span class="trend-down">↓ 8.2%</span>
            <span>较昨日</span>
          </div>
        </div>
        <div class="overview-card">
          <div class="card-title">平均响应时间</div>
          <div class="card-value">235ms</div>
          <div class="card-footer">
            <span class="trend-down">↓ 5.1%</span>
            <span>较上周</span>
          </div>
        </div>
        <div class="overview-card">
          <div class="card-title">服务调用量</div>
          <div class="card-value">1,892</div>
          <div class="card-footer">
            <span class="trend-up">↑ 23.6%</span>
            <span>较昨日</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 热门服务 -->
    <div class="hot-services">
      <div class="section-title">热门服务</div>
      <el-table :data="hotServices" style="width: 100%">
        <el-table-column prop="name" label="服务名称" width="280" />
        <el-table-column prop="calls" label="调用次数" width="120" />
        <el-table-column prop="avgTime" label="平均响应时间" width="150" />
        <el-table-column prop="errorRate" label="错误率" />
        <el-table-column label="趋势" width="200">
          <template #default="scope">
            <div class="trend-chart"></div>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ArrowLeft, ArrowRight, ArrowDown, Refresh } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'

const router = useRouter()

// 模拟数据
const timeSlots = ref([
  {
    time: '17:43',
    services: [
      { name: 'cargo-detail', count: 286, error: false },
      { name: 'ymm-cargo-search-app', count: 225, error: false },
      { name: 'sp-cargo-search-service', count: 76, error: false },
      { name: 'cargo-publish', count: 63, error: false },
      { name: 'ymm-appm-service', count: 25, error: false },
      { name: 'trade-notary-app', count: 20, error: false },
      { name: 'trade-skypeye-app', count: 18, error: false },
      { name: 'cargo-cm', count: 16, error: false },
      { name: 'navigation-service', count: 16, error: false },
      { name: 'cargo-recommendation-app', count: 15, error: false },
      { name: 'ymm-appm-app', count: 10, error: false },
    ]
  },
  {
    time: '17:42',
    services: [
      { name: 'cargo-detail', count: 1063, error: true },
      { name: 'service-page-app', count: 201, error: false },
      { name: 'trade-notary-app', count: 142, error: false },
      { name: 'cargo-recommendation-app', count: 436, error: false },
      { name: 'cargo-publish', count: 419, error: false },
      { name: 'cargo-cm', count: 313, error: false },
      { name: 'ymm-cargo-search-app', count: 269, error: false },
      { name: 'ymm-cargo-app', count: 143, error: false },
      { name: 'cargo-notify-app', count: 136, error: false },
      { name: 'trade-manhattan-app', count: 106, error: false },
      { name: 'ymm-appm-app', count: 9, error: false },
    ]
  },
  {
    time: '17:41',
    services: [
      { name: 'cargo-detail', count: 272, error: false },
      { name: 'cargo-publish', count: 36, error: false },
      { name: 'trade-notary-app', count: 29, error: false },
      { name: 'nav-route-web', count: 23, error: false },
      { name: 'ymm-cargo-search-app', count: 18, error: false },
      { name: 'scheduler-server', count: 12, error: false },
      { name: 'ymm-appm-service', count: 12, error: false },
      { name: 'cargo-cm', count: 12, error: false },
      { name: 'navigation-service', count: 12, error: false },
      { name: 'cargo-service', count: 11, error: false },
      { name: 'ymm-appm-app', count: 5, error: false },
      { name: 'nav-peccancy-area-app', count: 3, error: false },
      { name: 'nav-data-web', count: 2, error: false },
    ]
  },
  {
    time: '17:40',
    services: [
      { name: 'cargo-detail', count: 350, error: false },
      { name: 'ymm-cargo-search-app', count: 91, error: false },
      { name: 'bedou-api', count: 41, error: false },
      { name: 'cargo-publish', count: 30, error: false },
      { name: 'trade-notary-app', count: 30, error: false },
      { name: 'ymm-position-service', count: 15, error: false },
      { name: 'ymm-activity-service', count: 14, error: false },
      { name: 'cargo-cm', count: 12, error: false },
      { name: 'navigation-service', count: 12, error: false },
      { name: 'scheduler-server', count: 10, error: false },
      { name: 'ymm-appm-app', count: 3, error: false },
    ]
  },
  {
    time: '17:39',
    services: [
      { name: 'cargo-detail', count: 578, error: false },
      { name: 'ymm-cargo-search-app', count: 382, error: false },
      { name: 'cargo-publish', count: 114, error: false },
      { name: 'cargo-cm', count: 104, error: false },
      { name: 'sp-cargo-search-service', count: 97, error: false },
      { name: 'cargo-recommendation-app', count: 97, error: false },
      { name: 'ymm-cargo-app', count: 44, error: false },
      { name: 'trade-notary-app', count: 37, error: false },
      { name: 'trade-manhattan-app', count: 29, error: false },
      { name: 'ymm-activity-service', count: 24, error: false },
      { name: 'ymm-appm-app', count: 8, error: false },
      { name: 'nav-data-web', count: 3, error: false },
      { name: 'nav-peccancy-area-app', count: 2, error: false },
      { name: 'nav-route-web', count: 1, error: false },
    ]
  },
  {
    time: '17:38',
    services: [
      { name: 'cargo-detail', count: 312, error: false },
      { name: 'cargo-publish', count: 42, error: false },
      { name: 'trade-notary-app', count: 29, error: false },
      { name: 'bedou-api', count: 25, error: false },
      { name: 'ymm-cargo-search-app', count: 22, error: false },
      { name: 'nav-route-web', count: 18, error: false },
      { name: 'ymm-appm-service', count: 15, error: false },
      { name: 'navigation-service', count: 15, error: false },
      { name: 'cargo-service', count: 12, error: false },
      { name: 'ymm-position-service', count: 10, error: false },
      { name: 'ymm-appm-app', count: 15, error: false },
      { name: 'nav-data-web', count: 4, error: false },
    ]
  }
])

// 热门服务数据
const hotServices = ref([
  {
    name: 'cargo-detail',
    calls: 12546,
    avgTime: '187ms',
    errorRate: '0.5%'
  },
  {
    name: 'ymm-cargo-search-app',
    calls: 10283,
    avgTime: '234ms',
    errorRate: '0.8%'
  },
  {
    name: 'cargo-publish',
    calls: 8765,
    avgTime: '195ms',
    errorRate: '1.2%'
  },
  {
    name: 'trade-notary-app',
    calls: 7632,
    avgTime: '245ms',
    errorRate: '0.7%'
  },
  {
    name: 'sp-cargo-search-service',
    calls: 6421,
    avgTime: '156ms',
    errorRate: '0.3%'
  }
])

const handleCommand = (command) => {
  // 路由跳转
  router.push(command)
}
</script>

<style scoped>
.monitor-dashboard {
  background: white;
  padding: 16px;
  border-radius: 4px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow: auto;
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.dashboard-title h2 {
  font-size: 16px;
  font-weight: 600;
  color: #333;
  margin: 0;
}

.filter-area {
  display: flex;
  align-items: center;
  gap: 5px;
}

.time-filter {
  display: flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
  font-size: 13px;
  color: #606266;
}

.service-list {
  display: flex;
  gap: 12px;
  overflow-x: auto;
  padding-bottom: 8px;
}

.service-list::-webkit-scrollbar {
  height: 8px;
}

.service-list::-webkit-scrollbar-track {
  background: #f1f1f1;
  border-radius: 4px;
}

.service-list::-webkit-scrollbar-thumb {
  background: #888;
  border-radius: 4px;
}

.service-list::-webkit-scrollbar-thumb:hover {
  background: #555;
}

.time-slot {
  min-width: 200px;
  border: 1px solid #e8e8e8;
  border-radius: 2px;
  height: fit-content;
  flex-shrink: 0;
}

.time-header {
  padding: 8px 12px;
  background: #fafafa;
  border-bottom: 1px solid #e8e8e8;
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: 32px;
}

.time {
  font-weight: bold;
  color: #333;
  font-size: 13px;
}

.counter {
  color: #1890ff;
  font-size: 13px;
}

.service-items {
  padding: 4px 8px;
}

.service-item {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  border-bottom: 1px solid #f0f0f0;
  font-size: 13px;
}

.service-item:last-child {
  border-bottom: none;
}

.service-name {
  color: #1890ff;
  cursor: pointer;
  max-width: 70%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-item.error-bg {
  background-color: #ff4d4f;
  border-radius: 4px;
  padding: 8px 12px;
  margin: 0 -12px;
}

.service-item.error-bg .service-name,
.service-item.error-bg .service-count {
  color: white;
}

.service-count {
  color: #666;
  font-family: monospace;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
  margin-bottom: 16px;
}

.card-container {
  display: flex;
  gap: 24px;
  margin-bottom: 8px;
}

.overview-card {
  flex: 1;
  min-width: 200px;
  padding: 20px;
  background: #fafafa;
  border-radius: 4px;
  border: 1px solid #f0f0f0;
}

.card-title {
  font-size: 14px;
  color: #666;
  margin-bottom: 8px;
}

.card-value {
  font-size: 24px;
  font-weight: bold;
  color: #333;
  margin-bottom: 8px;
}

.card-footer {
  font-size: 12px;
  color: #999;
}

.trend-up {
  color: #52c41a;
  margin-right: 4px;
}

.trend-down {
  color: #ff4d4f;
  margin-right: 4px;
}

.hot-services {
  margin-top: 8px;
}

.trend-chart {
  width: 100%;
  height: 30px;
  background: linear-gradient(90deg, rgba(24, 144, 255, 0.1) 0%, rgba(24, 144, 255, 0.3) 100%);
  border-radius: 2px;
}
</style> 