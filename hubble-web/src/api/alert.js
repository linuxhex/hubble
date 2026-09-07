import request from '@/utils/request.js'

/**
 * 创建预警配置
 */
export function createAlertConfig(data) {
  return request.post('/alert-config/mgmt', data)
}

/**
 * 更新预警配置
 */
export function updateAlertConfig(id, data) {
  return request.put(`/alert-config/mgmt/${id}`, data)
}

/**
 * 删除预警配置
 */
export function deleteAlertConfig(id) {
  return request.delete(`/alert-config/mgmt/${id}`)
}

/**
 * 获取预警配置详情
 */
export function getAlertConfigDetail(id) {
  return request.get(`/alert-config/query/${id}`)
}

/**
 * 分页查询预警配置列表
 */
export function getAlertConfigList(params) {
  return request.get('/alert-config/query', { params })
}

/**
 * 启用预警配置
 */
export function enableAlertConfig(id) {
  return request.put(`/alert-config/mgmt/${id}/enable`)
}

/**
 * 禁用预警配置
 */
export function disableAlertConfig(id) {
  return request.put(`/alert-config/mgmt/${id}/disable`)
}

/**
 * 分页查询预警数据列表
 */
export function getAlertDataList(params) {
  return request.get('/alert-data/query', { params })
}

/**
 * 获取预警统计数据
 */
export function getAlertStatistics(configId, timeRange) {
  return request.get(`/alert-data/query/statistics/${configId}`, {
    params: timeRange ? { timeRange } : undefined
  })
}

/**
 * 按服务维度查看健康状态
 */
export function getServiceHealth(timeRange) {
  return request.get('/alert-data/service-health', {
    params: timeRange ? { timeRange } : undefined
  })
}

/**
 * 分钟级健康时间线
 */
export function getMinuteTimeline(timeRange) {
  return request.get('/alert-data/minute-timeline', {
    params: timeRange ? { timeRange } : undefined
  })
}

export function getServiceDrillDown(serviceName, timeRange) {
  return request.get('/alert-data/service-drilldown', {
    params: { serviceName, timeRange: timeRange || '15m' }
  })
}
