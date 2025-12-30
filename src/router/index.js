import { createRouter, createWebHistory } from 'vue-router'
import MonitorDashboard from '../components/MonitorDashboard.vue'
import AbnormalDashboard from '../components/AbnormalDashboard.vue'
import GatewayDashboard from '../components/GatewayDashboard.vue'
import GatewayTrace from '../components/GatewayTrace.vue'
import GatewayLogs from '../components/GatewayLogs.vue'

const routes = [
  {
    path: '/',
    redirect: '/monitor'
  },
  {
    path: '/monitor',
    name: 'Monitor',
    component: MonitorDashboard
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
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router