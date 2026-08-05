import request from '@/utils/request'

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
