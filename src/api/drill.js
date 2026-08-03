import request from '@/utils/request.js'

/**
 * 下钻：查监控项对应服务的实时日志
 */
export function getServiceLogs(params) {
  return request.get('/alert-data/service-logs', {
    params: {
      configId: params.configId,
      timeRange: params.timeRange || '15m',
      limit: params.limit || 100
    }
  })
}

/**
 * 下钻：按 traceId 查跨服务日志（轻量链路）
 */
export function getTraceLogs(params) {
  return request.get('/alert-data/trace-logs', {
    params: {
      traceId: params.traceId,
      timeRange: params.timeRange || '1h',
      limit: params.limit || 200
    }
  })
}
