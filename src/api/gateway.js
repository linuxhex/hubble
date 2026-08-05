import request from '@/utils/request.js'

export function getGatewayOverview(params) {
  return request.get('/gateway/overview', { params })
}

export function getGatewayTrend(params) {
  return request.get('/gateway/trend', { params })
}

export function getGatewayHotApis(params) {
  return request.get('/gateway/hot-apis', { params })
}

export function getApiDegradation(params) {
  return request.get('/gateway/degradation', { params })
}
