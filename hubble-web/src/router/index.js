import { createRouter, createWebHistory } from 'vue-router'
import { nextTick } from 'vue'
import MonitorDashboard from '../components/MonitorDashboard.vue'
import AbnormalDashboard from '../components/AbnormalDashboard.vue'
import GatewayDashboard from '../components/GatewayDashboard.vue'
import GatewayTrace from '../components/GatewayTrace.vue'
import GatewayLogs from '../components/GatewayLogs.vue'
import UserBehavior from '../components/UserBehavior.vue'
import UserBehaviorTraceQuery from '../components/UserBehaviorTraceQuery.vue'
import TraceQuery from '../components/TraceQuery.vue'
import KeywordLogQuery from '../components/KeywordLogQuery.vue'
import TraceManagement from '../components/TraceManagement.vue'
import SlsKeywordManagement from '../components/SlsKeywordManagement.vue'
import AlertConfigManagement from '../components/AlertConfigManagement.vue'
import Unauthorized from '../components/Unauthorized.vue'
import Login from '../components/Login.vue'
import WidgetDashboard from '../components/WidgetDashboard.vue'
import ApiDegradation from '../components/ApiDegradation.vue'
import TrafficSurgeDashboard from '../components/TrafficSurgeDashboard.vue'
import MiddlewareDashboard from '../components/MiddlewareDashboard.vue'
import BizAnalysisDashboard from '../components/BizAnalysisDashboard.vue'

const PUBLIC_PATHS = ['/login', '/unauthorized']

const routes = [
  {
    path: '/',
    redirect: '/gateway'
  },
  {
    path: '/login',
    name: 'Login',
    component: Login
  },
  {
    path: '/monitor',
    redirect: '/gateway'
  },
  {
    path: '/abnormal',
    name: 'Abnormal',
    component: AbnormalDashboard
  },
  {
    path: '/gateway',
    name: 'Gateway',
    component: GatewayDashboard
  },
  {
    path: '/gateway/trace',
    name: 'GatewayTrace',
    component: GatewayTrace
  },
  {
    path: '/gateway/logs',
    name: 'GatewayLogs',
    component: GatewayLogs
  },
  {
    path: '/user-behavior',
    name: 'UserBehavior',
    component: UserBehavior
  },
  {
    path: '/trace-query',
    name: 'TraceQuery',
    component: TraceQuery
  },
  {
    path: '/user-behavior-trace',
    name: 'UserBehaviorTraceQuery',
    component: UserBehaviorTraceQuery
  },
  {
    path: '/keyword-log-query',
    name: 'KeywordLogQuery',
    component: KeywordLogQuery
  },
  {
    path: '/trace-management',
    name: 'TraceManagement',
    component: TraceManagement
  },
  {
    path: '/sls-keyword-management',
    name: 'SlsKeywordManagement',
    component: SlsKeywordManagement
  },
  {
    path: '/alert-config',
    name: 'AlertConfigManagement',
    component: AlertConfigManagement
  },
  {
    path: '/unauthorized',
    name: 'Unauthorized',
    component: Unauthorized
  },
  {
    path: '/widget-dashboard',
    name: 'WidgetDashboard',
    component: WidgetDashboard
  },
  {
    path: '/degradation-ranking',
    name: 'ApiDegradation',
    component: ApiDegradation
  },
  {
    path: '/traffic-surge',
    name: 'TrafficSurgeDashboard',
    component: TrafficSurgeDashboard
  },
  {
    path: '/middleware',
    name: 'MiddlewareDashboard',
    component: MiddlewareDashboard
  },
  {
    path: '/biz-analysis',
    name: 'BizAnalysis',
    component: BizAnalysisDashboard
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  if (PUBLIC_PATHS.includes(to.path)) {
    next()
    return
  }

  const token = localStorage.getItem('auth_token')
  if (!token) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  if (to.path === '/biz-analysis' || to.path === '/abnormal') {
    const user = JSON.parse(localStorage.getItem('auth_user') || '{}')
    if (user.nickname !== 'lianzi') {
      next('/unauthorized')
      return
    }
  }

  next()
})

// 支持锚点跳转：告警详情链接带 #anchor，滚动到对应区块
router.afterEach((to) => {
  if (to.hash) {
    nextTick(() => {
      const el = document.querySelector(to.hash)
      if (el) el.scrollIntoView({ behavior: 'smooth' })
    })
  }
})

export default router