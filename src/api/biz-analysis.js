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
