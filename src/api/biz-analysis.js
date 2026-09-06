import request from '@/utils/request.js'

export function getOverview() {
  return request.get('/biz-analysis/overview')
}

export function getMonthlyTrend() {
  return request.get('/biz-analysis/monthly-trend')
}

export function getDaily(days = 30) {
  return request.get('/biz-analysis/daily', { params: { days } })
}

export function getScenario() {
  return request.get('/biz-analysis/scenario')
}

export function getActiveUsers(limit = 20) {
  return request.get('/biz-analysis/active-users', { params: { limit } })
}

export function getAppActive(days = 30) {
  return request.get('/biz-analysis/app-active', { params: { days } })
}

export function getMauTrend() {
  return request.get('/biz-analysis/mau-trend')
}

export function getYearlyComparison() {
  return request.get('/biz-analysis/yearly-comparison')
}

export function getRevenueTrend(days = 30) {
  return request.get('/biz-analysis/revenue-trend', { params: { days } })
}

export function getUtilizationTrend(days = 30) {
  return request.get('/biz-analysis/utilization-trend', { params: { days } })
}

export function getRegionDistribution(days = 30) {
  return request.get('/biz-analysis/region-distribution', { params: { days } })
}

export function getStationRanking(days = 30, limit = 20) {
  return request.get('/biz-analysis/station-ranking', { params: { days, limit } })
}

export function getHourlyDistribution(days = 7) {
  return request.get('/biz-analysis/hourly-distribution', { params: { days } })
}

export function getRealtimeOrder() {
  return request.get('/biz-analysis/realtime-order')
}

export function getIdleStationRanking(days = 30, limit = 20) {
  return request.get('/biz-analysis/idle-station-ranking', { params: { days, limit } })
}
