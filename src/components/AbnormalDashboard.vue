<template>
  <div class="abnormal-dashboard">
    <!-- 过滤器 -->
    <div class="dashboard-header">
      <div class="dashboard-title">
        <h2>异常大盘</h2>
      </div>
      <div class="filter-area">
        <el-dropdown @command="handleCommand">
          <span class="time-filter">
            异常大盘 <el-icon><ArrowDown /></el-icon>
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

    <!-- 异常服务列表 -->
    <div class="service-list">
      <div v-for="(timeSlot, index) in timeSlots" :key="index" class="time-slot">
        <div class="time-header">
          <span class="time">{{ timeSlot.time }}</span>
          <span class="counter">事件</span>
        </div>
        <div class="service-items">
          <div v-for="(service, sIndex) in timeSlot.services" :key="sIndex" 
               class="service-item"
               :class="{ 'error-bg': service.count > 500 }">
            <div class="service-name">{{ service.name }}</div>
            <div class="service-count">{{ service.count }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'

const router = useRouter()

// 模拟数据
const timeSlots = ref([
  {
    time: '17:43',
    services: [
      { name: 'cargo-detail', count: 286 },
      { name: 'ymm-cargo-search-app', count: 225 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:42',
    services: [
      { name: 'cargo-detail', count: 1063 },
      { name: 'service-page-app', count: 201 },
      { name: 'trade-notary-app', count: 142 },
      { name: 'cargo-recommendation-app', count: 436 },
      { name: 'cargo-publish', count: 419 },
      { name: 'cargo-cm', count: 313 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:41',
    services: [
      { name: 'cargo-detail', count: 272 },
      { name: 'cargo-publish', count: 36 },
      { name: 'trade-notary-app', count: 29 },
      { name: 'nav-route-web', count: 23 },
      { name: 'ymm-cargo-search-app', count: 18 },
      { name: 'scheduler-server', count: 12 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:40',
    services: [
      { name: 'cargo-detail', count: 350 },
      { name: 'ymm-cargo-search-app', count: 91 },
      { name: 'bedou-api', count: 41 },
      { name: 'cargo-publish', count: 30 },
      { name: 'trade-notary-app', count: 30 },
      { name: 'ymm-position-service', count: 15 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:39',
    services: [
      { name: 'cargo-detail', count: 578 },
      { name: 'ymm-cargo-search-app', count: 382 },
      { name: 'cargo-publish', count: 114 },
      { name: 'cargo-cm', count: 104 },
      { name: 'sp-cargo-search-service', count: 97 },
      { name: 'cargo-recommendation-app', count: 97 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:38',
    services: [
      { name: 'cargo-detail', count: 312 },
      { name: 'cargo-publish', count: 42 },
      { name: 'trade-notary-app', count: 29 },
      { name: 'bedou-api', count: 25 },
      { name: 'ymm-cargo-search-app', count: 22 },
      { name: 'nav-route-web', count: 18 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:37',
    services: [
      { name: 'cargo-detail', count: 457 },
      { name: 'cargo-publish', count: 121 },
      { name: 'cargo-cm', count: 107 },
      { name: 'ymm-cargo-search-app', count: 103 },
      { name: 'cargo-recommendation-app', count: 90 },
      { name: 'ymm-cargo-app', count: 48 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:36',
    services: [
      { name: 'cargo-detail', count: 635 },
      { name: 'ymm-cargo-search-app', count: 174 },
      { name: 'cargo-cm', count: 75 },
      { name: 'cargo-notify-app', count: 49 },
      { name: 'sp-cargo-search-service', count: 43 },
      { name: 'cargo-publish', count: 32 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:35',
    services: [
      { name: 'cargo-detail', count: 327 },
      { name: 'cargo-publish', count: 35 },
      { name: 'trade-notary-app', count: 24 },
      { name: 'nav-route-app', count: 24 },
      { name: 'bedou-api', count: 22 },
      { name: 'ymm-activity-service', count: 20 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:34',
    services: [
      { name: 'cargo-detail', count: 300 },
      { name: 'cargo-cm', count: 28 },
      { name: 'cargo-publish', count: 27 },
      { name: 'navigation-service', count: 24 },
      { name: 'trade-notary-app', count: 23 },
      { name: 'ymm-activity-service', count: 22 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:33',
    services: [
      { name: 'cargo-detail', count: 1113 },
      { name: 'trade-om-app', count: 116 },
      { name: 'cargo-recommendation-app', count: 458 },
      { name: 'cargo-publish', count: 385 },
      { name: 'cargo-cm', count: 290 },
      { name: 'ymm-cargo-search-app', count: 284 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:32',
    services: [
      { name: 'cargo-detail', count: 378 },
      { name: 'cargo-publish', count: 123 },
      { name: 'cargo-cm', count: 26 },
      { name: 'cargo-recommendation-app', count: 23 },
      { name: 'trade-notary-app', count: 22 },
      { name: 'ymm-appm-service', count: 21 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:31',
    services: [
      { name: 'cargo-detail', count: 371 },
      { name: 'cargo-cm', count: 69 },
      { name: 'ymm-cargo-search-app', count: 68 },
      { name: 'cargo-publish', count: 53 },
      { name: 'trade-notary-app', count: 42 },
      { name: 'ymm-cargo-app', count: 36 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:30',
    services: [
      { name: 'cargo-detail', count: 240 },
      { name: 'bedou-api', count: 33 },
      { name: 'cargo-service', count: 34 },
      { name: 'cargo-publish', count: 30 },
      { name: 'ymm-appm-service', count: 28 },
      { name: 'nav-route-web', count: 28 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:29',
    services: [
      { name: 'cargo-detail', count: 290 },
      { name: 'cargo-service', count: 34 },
      { name: 'ymm-cargo-search-app', count: 31 },
      { name: 'trade-notary-app', count: 30 },
      { name: 'cargo-publish', count: 28 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:28',
    services: [
      { name: 'cargo-service', count: 68 },
      { name: 'cargo-detail', count: 642 },
      { name: 'ymm-cargo-search-app', count: 59 },
      { name: 'cargo-publish', count: 52 },
      { name: 'cargo-recommendation-app', count: 45 },
      { name: 'nav-route-web', count: 40 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:27',
    services: [
      { name: 'cargo-detail', count: 392 },
      { name: 'cargo-cm', count: 40 },
      { name: 'ymm-cargo-search-app', count: 38 },
      { name: 'cargo-publish', count: 30 },
      { name: 'bedou-api', count: 26 },
      { name: 'navigation-service', count: 24 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  },
  {
    time: '17:26',
    services: [
      { name: 'cargo-detail', count: 327 },
      { name: 'trade-notary-app', count: 37 },
      { name: 'ymm-cargo-search-app', count: 29 },
      { name: 'cargo-publish', count: 28 },
      { name: 'cargo-cm', count: 20 },
      { name: 'ymm-appm-service', count: 18 },
      { name: 'sp-cargo-search-service', count: 76 },
      { name: 'cargo-publish', count: 63 },
      { name: 'ymm-appm-service', count: 25 },
      { name: 'trade-notary-app', count: 20 },
    ]
  }
])

const handleCommand = (command) => {
  // 路由跳转
  router.push(command)
}
</script>

<style scoped>
.abnormal-dashboard {
  background: white;
  padding: 10px;
  border-radius: 4px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow: auto;
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
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
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 8px;
  overflow-y: auto;
  padding-bottom: 4px;
  max-height: calc(100vh - 100px);
}

.time-slot {
  min-width: 0;
  border: 1px solid #e8e8e8;
  border-radius: 2px;
  height: fit-content;
}

.time-header {
  padding: 4px 8px;
  background: #fafafa;
  border-bottom: 1px solid #e8e8e8;
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: 24px;
}

.time {
  font-weight: bold;
  color: #333;
  font-size: 12px;
}

.counter {
  color: #1890ff;
  font-size: 11px;
}

.service-items {
  padding: 2px 4px;
  max-height: 240px;
  overflow-y: auto;
}

.service-item {
  display: flex;
  justify-content: space-between;
  padding: 3px 0;
  border-bottom: 1px solid #f0f0f0;
  font-size: 11px;
}

.service-item:last-child {
  border-bottom: none;
}

.service-name {
  color: #1890ff;
  cursor: pointer;
  max-width: 65%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-item.error-bg {
  background-color: #ff4d4f;
  border-radius: 2px;
  padding: 3px 4px;
  margin: 0 -4px;
}

.service-item.error-bg .service-name,
.service-item.error-bg .service-count {
  color: white;
}

.service-count {
  color: #666;
  font-family: monospace;
}
</style> 