import request from '@/utils/request.js'

/**
 * 查询错误类型排行榜
 */
export function getTopErrorTypes(params) {
  return request.get(`/error-analysis/query/error-types/${params.alertConfigId}`, {
    params: {
      timeRange: params.timeRange || '30m',
      limit: params.limit || 10
    }
  })
}

/**
 * 获取错误类型详细信息
 */
export function getErrorTypeDetail(typeId) {
  return request.get(`/error-analysis/query/error-type-detail/${typeId}`)
}

/**
 * 查询错误趋势数据
 */
export function getErrorTrend(params) {
  return request.get(`/error-analysis/query/error-trend/${params.alertConfigId}`, {
    params: {
      timeRange: params.timeRange || '1d',
      interval: params.interval || '1h',
      typeId: params.typeId
    }
  })
}

/**
 * 查询Top错误类型的趋势数据（用于折线图）
 */
export function getTopErrorTypesTrend(params) {
  return request.get(`/error-analysis/query/error-types-trend/${params.alertConfigId}`, {
    params: {
      timeRange: params.timeRange || '30m',
      interval: params.interval || '5m'
    }
  })
}
