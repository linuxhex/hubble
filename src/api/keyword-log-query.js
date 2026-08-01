import request from '@/utils/request'

/**
 * 查询关键字日志
 */
export function queryKeywordLogs(data) {
  return request.post('/traces/query/keyword', data)
}
