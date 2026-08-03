import request from '@/utils/request'

/**
 * 查询网关日志
 */
export function queryGatewayLogs(data) {
  return request.post('/gateway/logs/query', data)
}

/**
 * @deprecated 使用 queryGatewayLogs 替代
 */
export const queryKeywordLogs = queryGatewayLogs
