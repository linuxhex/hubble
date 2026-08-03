import request from '@/utils/request'

export function getTraceChain(traceId, timeRange = '1h') {
  return request({
    url: `/gateway/trace/${encodeURIComponent(traceId)}`,
    method: 'get',
    params: { timeRange }
  })
}
