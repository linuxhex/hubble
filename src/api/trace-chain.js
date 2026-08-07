import request from '@/utils/request'

export function searchTracesByApi(apiPath, timeRange = '1h', limit = 20) {
  return request({
    url: '/gateway/trace/search',
    method: 'get',
    params: { apiPath, timeRange, limit }
  })
}

export function getTraceChain(traceId, timeRange = '1h', timestamp = null) {
  const params = { timeRange }
  if (timestamp) {
    params.timestamp = timestamp
  }
  return request({
    url: `/gateway/trace/${encodeURIComponent(traceId)}`,
    method: 'get',
    params
  })
}
