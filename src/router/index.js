import { createRouter, createWebHistory } from 'vue-router'
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
import AlertOverview from '../components/AlertOverview.vue'
import AlertDashboard from '../components/AlertDashboard.vue'
import Unauthorized from '../components/Unauthorized.vue'
import Login from '../components/Login.vue'
import WidgetDashboard from '../components/WidgetDashboard.vue'
import TrendDashboard from '../components/TrendDashboard.vue'
import SecondChart from '../components/SecondChart.vue'
import ApiDegradation from '../components/ApiDegradation.vue'

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
    path: '/alert-overview',
    name: 'AlertOverview',
    component: AlertOverview
  },
  {
    path: '/alert-dashboard',
    name: 'AlertDashboard',
    component: AlertDashboard
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
    path: '/trend-dashboard',
    name: 'TrendDashboard',
    component: TrendDashboard
  },
  {
    path: '/second-chart',
    name: 'SecondChart',
    component: SecondChart
  },
  {
    path: '/degradation-ranking',
    name: 'ApiDegradation',
    component: ApiDegradation
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

  next()
})

export default router