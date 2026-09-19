import request from '@/utils/request.js'

export function getServiceLoadApps() {
  return request.get('/service-load/apps')
}

export function getServiceLoadTrend(appName, startDate) {
  return request.get('/service-load/trend', { params: { appName, startDate } })
}

export function getServiceLoadList(startDate) {
  return request.get('/service-load/list', { params: { startDate } })
}

export function getAssessment(days) {
  return request.get('/service-load/assessment', { params: { days } })
}

export function manualCollectServiceLoad(date) {
  // 采集需逐服务查询 SLS/Grafana，耗时约1-3分钟，单独放宽超时
  return request.post('/service-load/collect', null, { params: { date }, timeout: 600000 })
}

export function generateDemoData() {
  return request.post('/service-load/generate-demo-data')
}
