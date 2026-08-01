import request from '@/utils/request'

/**
 * 查询用户行为轨迹
 */
export function queryUserBehaviorTrace(data) {
  return request.post('/traces/query/user-behavior', data)
}


