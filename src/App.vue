<script setup>
import { ref, computed, reactive, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { HomeFilled, Monitor, Connection, Timer, Fold, Expand, Bell, DataLine, Warning, ArrowLeft, ArrowRight, Document, Refresh, Sort } from '@element-plus/icons-vue'

const route = useRoute()
const isCollapse = ref(false)

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
  if (route.path === '/trend-dashboard') return '/trend-dashboard'
  if (route.path === '/second-chart') return '/second-chart'
  if (route.path === '/degradation-ranking') return '/degradation-ranking'
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
          <span>概览</span>
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
        <el-menu-item index="/abnormal">
          <el-icon><Warning /></el-icon>
          <span>异常大盘</span>
        </el-menu-item>
        <el-menu-item index="/degradation-ranking">
          <el-icon><Sort /></el-icon>
          <span>接口劣化</span>
        </el-menu-item>
      </el-menu>
    </div>

    <!-- 主内容区 -->
    <div class="main-content">
      <!-- 顶部导航栏 -->
      <div class="header">
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
          <el-avatar size="small" />
        </div>
      </div>

      <!-- 内容区域 -->
      <div class="content">
        <router-view />
      </div>
    </div>
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
</style>
