<script setup>
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { HomeFilled, Monitor, Connection, Grid, TrendCharts, Timer, List, Fold, Expand, Bell, DataLine, Warning, ArrowLeft, ArrowRight, Document, Refresh, Plus, Edit, Delete, VideoPlay, VideoPause, Setting } from '@element-plus/icons-vue'

const route = useRoute()
const isCollapse = ref(false)

const toggleCollapse = () => {
  isCollapse.value = !isCollapse.value
}

const activeIndex = computed(() => {
  if (route.path === '/monitor') return '/monitor'
  if (route.path === '/abnormal') return '/abnormal'
  if (route.path === '/gateway') return '/gateway'
  if (route.path === '/gateway/trace') return '/gateway/trace'
  if (route.path === '/gateway/logs') return '/gateway/logs'
  if (route.path === '/trace-query') return '/trace-query'
  if (route.path === '/user-behavior-trace') return '/user-behavior-trace'
  if (route.path === '/keyword-log-query') return '/keyword-log-query'
  if (route.path === '/trace-management') return '/trace-management'
  if (route.path === '/sls-keyword-management') return '/sls-keyword-management'
  if (route.path === '/alert-config') return '/alert-config'
  if (route.path === '/alert-overview') return '/alert-overview'
  if (route.path === '/alert-dashboard') return '/alert-dashboard'
  if (route.path === '/unauthorized') return '/unauthorized'
  if (route.path === '/widget-dashboard') return '/widget-dashboard'
  if (route.path === '/trend-dashboard') return '/trend-dashboard'
  if (route.path === '/second-chart') return '/second-chart'
  return '1'
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
        <el-menu-item index="1">
          <el-icon><HomeFilled /></el-icon>
          <span>首页</span>
        </el-menu-item>
        
        <el-sub-menu index="2">
          <template #title>
            <el-icon><Monitor /></el-icon>
            <span>监控大盘</span>
          </template>
          <el-menu-item index="/monitor">
            <el-icon><DataLine /></el-icon>
            <span>监控大盘</span>
          </el-menu-item>
          <el-menu-item index="/abnormal">
            <el-icon><Warning /></el-icon>
            <span>异常大盘</span>
          </el-menu-item>
        </el-sub-menu>
        
        <el-sub-menu index="3">
          <template #title>
            <el-icon><Connection /></el-icon>
            <span>网关大盘</span>
          </template>
          <el-menu-item index="/gateway">
            <el-icon><DataLine /></el-icon>
            <span>概览</span>
          </el-menu-item>
          <el-menu-item index="/gateway/trace">
            <el-icon><Connection /></el-icon>
            <span>链路详情</span>
          </el-menu-item>
          <el-menu-item index="/gateway/logs">
            <el-icon><Document /></el-icon>
            <span>日志搜索</span>
          </el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="4">
          <template #title>
            <el-icon><Connection /></el-icon>
            <span>链路查询</span>
          </template>
          <el-menu-item index="/trace-query">
            <el-icon><Connection /></el-icon>
            <span>业务链路查询</span>
          </el-menu-item>
          <el-menu-item index="/user-behavior-trace">
            <el-icon><Timer /></el-icon>
            <span>用户行为轨迹</span>
          </el-menu-item>
          <el-menu-item index="/keyword-log-query">
            <el-icon><Document /></el-icon>
            <span>关键字日志查询</span>
          </el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="5">
          <template #title>
            <el-icon><Setting /></el-icon>
            <span>配置管理</span>
          </template>
          <el-menu-item index="/trace-management">
            <el-icon><Connection /></el-icon>
            <span>链路配置</span>
          </el-menu-item>
          <el-menu-item index="/sls-keyword-management">
            <el-icon><Document /></el-icon>
            <span>SLS模版管理</span>
          </el-menu-item>
          <el-menu-item index="/alert-config">
            <el-icon><Bell /></el-icon>
            <span>日志监控配置</span>
          </el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="6">
          <template #title>
            <el-icon><Bell /></el-icon>
            <span>日志监控</span>
          </template>
          <el-menu-item index="/alert-overview">
            <el-icon><DataLine /></el-icon>
            <span>日志监控大盘</span>
          </el-menu-item>
          <el-menu-item index="/alert-dashboard">
            <el-icon><Document /></el-icon>
            <span>日志监控详情</span>
          </el-menu-item>
        </el-sub-menu>
        <el-menu-item index="/widget-dashboard">
          <el-icon><Grid /></el-icon>
          <span>微控件大盘</span>
        </el-menu-item>
        <el-menu-item index="/trend-dashboard">
          <el-icon><TrendCharts /></el-icon>
          <span>趋势大盘</span>
        </el-menu-item>
        <el-menu-item index="/second-chart">
          <el-icon><Timer /></el-icon>
          <span>秒级监控图</span>
        </el-menu-item>
        <el-menu-item index="8">
          <el-icon><List /></el-icon>
          <span>自定义</span>
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
              <el-button type="primary" plain size="small">2020-11-09 17:00:00</el-button>
              <el-button type="primary" plain size="small">～</el-button>
              <el-button type="primary" plain size="small">2020-11-29 17:00:00</el-button>
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
  }

  &:hover {
    background-color: rgba(24, 144, 255, 0.8) !important;
  }
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
