import request from '@/utils/request.js'

/** 列出所有阈值配置 */
export function getThresholdList() {
  return request.get('/alert-threshold/list')
}

/** 更新阈值 */
export function updateThreshold(key, value) {
  return request.put(`/alert-threshold/${key}`, { value })
}
