<script setup>
import { ref, computed, reactive, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { HomeFilled, Monitor, Connection, Timer, Fold, Expand, Bell, BellFilled, DataLine, Warning, ArrowLeft, ArrowRight, Document, Refresh, Sort, SwitchButton, TrendCharts, Box, Search, Setting, Aim, Files, Histogram, PieChart } from '@element-plus/icons-vue'
import AiChat from '@/components/AiChat.vue'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const isCollapse = ref(false)

const isPublicPage = computed(() => {
  return route.path === '/login' || route.path === '/unauthorized'
})

const toggleCollapse = () => {
  isCollapse.value = !isCollapse.value
}

const activeIndex = computed(() => {
  if (route.path === '/' || route.path === '/monitor' || route.path === '/gateway') return '/gateway'
  if (route.path === '/abnormal') return '/abnormal'
  if (route.path === '/gateway/trace') return '/gateway/trace'
  if (route.path === '/user-behavior') return '/user-behavior'
  if (route.path === '/gateway/logs') return '/gateway/logs'
  if (route.path === '/unauthorized') return '/unauthorized'
  if (route.path === '/widget-dashboard') return '/widget-dashboard'
  if (route.path === '/degradation-ranking') return '/degradation-ranking'
  if (route.path === '/alert-config') return '/alert-config'
  if (route.path === '/biz-analysis') return '/biz-analysis'
  return '1'
})

const timeRange = reactive({
  startTime: '',
  endTime: ''
})

let timeTimer = null

const updateTime = () => {
  const now = new Date()
  const oneHourAgo = new Date(now.getTime() - 60 * 60 * 1000)
  timeRange.startTime = formatDate(oneHourAgo)
  timeRange.endTime = formatDate(now)
}

const formatDate = (date) => {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const seconds = String(date.getSeconds()).padStart(2, '0')
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
}

function handleLogout() {
  authStore.logout()
  router.replace('/login')
}

onMounted(() => {
  updateTime()
  timeTimer = setInterval(updateTime, 1000)
})

onUnmounted(() => {
  if (timeTimer) {
    clearInterval(timeTimer)
  }
})
</script>

<template>
  <div class="app-container">
    <!-- 公开页面（登录/未授权）不显示侧边栏 -->
    <template v-if="!isPublicPage">
      <!-- 左侧导航栏 -->
      <div class="sidebar" :class="{ 'collapsed': isCollapse }">
        <div class="logo">
          <img v-if="!isCollapse" alt="Hubble" src="./assets/hubble-logo.svg" />
          <img v-else alt="H" src="./assets/hubble-logo-small.svg" />
        </div>
        <el-menu
          :default-active="activeIndex"
          class="sidebar-menu"
          background-color="#001529"
          text-color="#fff"
          :collapse="isCollapse"
          router
        >
          <!-- 导航菜单 -->
          <el-menu-item index="/gateway">
            <el-icon><DataLine /></el-icon>
            <span>网关概览</span>
          </el-menu-item>
          <el-menu-item v-if="authStore.user?.nickname === 'lianzi'" index="/abnormal">
            <el-icon><Warning /></el-icon>
            <span>异常大盘</span>
          </el-menu-item>
          <el-menu-item index="/degradation-ranking">
            <el-icon><Sort /></el-icon>
            <span>接口劣化</span>
          </el-menu-item>
          <el-menu-item index="/traffic-surge">
            <el-icon><TrendCharts /></el-icon>
            <span>流量暴涨</span>
          </el-menu-item>
          <el-menu-item index="/middleware">
            <el-icon><Box /></el-icon>
            <span>中间件</span>
          </el-menu-item>
          <el-menu-item index="/gateway/trace">
            <el-icon><Connection /></el-icon>
            <span>链路详情</span>
          </el-menu-item>
          <el-menu-item index="/user-behavior">
            <el-icon><Timer /></el-icon>
            <span>用户行为</span>
          </el-menu-item>
          <el-menu-item index="/gateway/logs">
            <el-icon><Document /></el-icon>
            <span>日志搜索</span>
          </el-menu-item>
          <el-menu-item index="/alert-config">
            <el-icon><Setting /></el-icon>
            <span>告警配置</span>
          </el-menu-item>
          <el-menu-item v-if="authStore.user?.nickname === 'lianzi'" index="/biz-analysis">
            <el-icon><TrendCharts /></el-icon>
            <span>经营分析</span>
          </el-menu-item>
        </el-menu>
      </div>
    </template>

    <!-- 主内容区 -->
    <div class="main-content" :class="{ 'full-width': isPublicPage }">
      <!-- 顶部导航栏（公开页面不显示） -->
      <div v-if="!isPublicPage" class="header">
        <div class="header-left">
          <el-icon class="fold-icon" @click="toggleCollapse">
            <Fold v-if="!isCollapse" />
            <Expand v-else />
          </el-icon>
        </div>
        <div class="header-right">
          <div class="time-selector">
            <el-button-group>
              <el-button type="primary" plain size="small">
                <el-icon><ArrowLeft /></el-icon>
              </el-button>
              <el-button type="primary" plain size="small">{{ timeRange.startTime }}</el-button>
              <el-button type="primary" plain size="small">～</el-button>
              <el-button type="primary" plain size="small">{{ timeRange.endTime }}</el-button>
              <el-button type="primary" plain size="small">
                <el-icon><ArrowRight /></el-icon>
              </el-button>
            </el-button-group>
          </div>
          <el-icon class="notification-icon"><Bell /></el-icon>
          <el-dropdown v-if="authStore.isLoggedIn" trigger="click">
            <div class="user-info">
              <el-avatar :size="28" :src="authStore.user?.avatar">
                {{ authStore.user?.nickname?.charAt(0) || '' }}
              </el-avatar>
              <span class="user-name">{{ authStore.user?.nickname || '用户' }}</span>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>
                  {{ authStore.user?.phone || '' }}
                </el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-avatar v-else size="small" />
        </div>
      </div>

      <!-- 内容区域 -->
      <div class="content" :class="{ 'full-height': isPublicPage }">
        <router-view />
      </div>
    </div>

    <!-- AI 对话浮动球（非公开页面显示） -->
    <AiChat v-if="!isPublicPage" />
  </div>
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body {
  width: 100%;
  height: 100%;
  overflow: hidden;
}

#app {
  width: 100%;
  height: 100%;
  margin: 0;
  padding: 0;
  max-width: none;
  text-align: left;
}

.app-container {
  display: flex;
  height: 100%;
  width: 100%;
  overflow: hidden;
}

.sidebar {
  width: 200px;
  height: 100%;
  background-color: #001529;
  position: relative;
  z-index: 1;
}

.sidebar.collapsed {
  width: 56px;
}

.logo {
  height: 64px;
  padding: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  overflow: hidden;
}

.logo img {
  height: 32px;
  transition: all 0.3s;
}

.sidebar.collapsed .logo {
  padding: 16px 8px;
}

.sidebar-menu {
  border-right: none;
  user-select: none;
  width: 100%;
}

.sidebar-menu:not(.el-menu--collapse) {
  width: 100%;
}

.main-content {
  flex: 1;
  height: 100%;
  overflow: hidden;
  background-color: #f0f2f5;
  position: relative;
  z-index: 2;
}

.header {
  height: 50px;
  background: white;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 1px 4px rgba(0,21,41,0.08);
}

.header-left .fold-icon {
  font-size: 18px;
  cursor: pointer;
  color: #666;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header-right .notification-icon {
  font-size: 18px;
  cursor: pointer;
  color: #666;
}

.header .time-selector {
  margin-right: 8px;
}

.header .el-button-group .el-button {
  margin: 0;
  height: 28px;
  line-height: 28px;
  padding: 0 10px;
}

.header .el-button--small {
  font-size: 12px;
}

.content {
  flex: 1;
  padding: 16px;
  overflow: auto;
  height: calc(100% - 50px);
}

.el-menu {
  border-right: none;
  background-color: #001529;
  height: 100%;
}

.el-menu-item {
  position: relative;
  z-index: 1;

  &.is-active {
    background-color: #1890ff !important;
    color: #fff !important;
  }

  &:hover {
    background-color: rgba(24, 144, 255, 0.8) !important;
    color: #fff !important;
  }
}

.el-sub-menu .el-sub-menu__title {
  color: #fff !important;
}

.el-sub-menu .el-sub-menu__title:hover {
  background-color: rgba(24, 144, 255, 0.3) !important;
  color: #fff !important;
}

.el-tabs {
  height: 100%;
  position: relative;
  z-index: 3;
}

.el-tabs__header {
  margin: 0;
  position: relative;
  background-color: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.el-tabs__content {
  height: calc(100% - 40px);
  padding: 16px;
  box-sizing: border-box;
  background-color: #fff;
}

.el-tabs__nav-wrap {
  position: relative;
  z-index: 4;
}

.el-tabs__item {
  position: relative;
  
  &.is-active {
    position: relative;
    z-index: 5;
  }
}

.layout-container {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.el-menu--collapse {
  width: 56px;
}

.el-menu-item .el-icon {
  margin-right: 10px;
  font-size: 18px;
}

.main-content.full-width {
  width: 100%;
}

.content.full-height {
  height: 100%;
  padding: 0;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: background-color 0.2s;
}

.user-info:hover {
  background-color: #f5f5f5;
}

.user-name {
  font-size: 14px;
  color: #333;
  max-width: 80px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
