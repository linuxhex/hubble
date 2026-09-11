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

export function manualCollectServiceLoad(date) {
  return request.post('/service-load/collect', null, { params: { date } })
}
