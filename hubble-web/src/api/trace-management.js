import request from '@/utils/request'

/**
 * 创建业务链路
 */
export function createTrace(data) {
  return request.post('/traces/mgmt', data)
}

/**
 * 获取业务链路列表
 */
export function getTraceList(params) {
  return request.get('/traces/query/list', { params })
}

/**
 * 获取业务链路详情
 */
export function getTraceDetail(id, includeChildren) {
  return request.get(`/traces/query/${id}`, {
    params: includeChildren ? { includeChildren: true } : undefined 
  })
}

/**
 * 更新业务链路
 */
export function updateTrace(id, data) {
  return request.put(`/traces/mgmt/${id}`, data)
}

/**
 * 删除业务链路
 */
export function deleteTrace(id) {
  return request.delete(`/traces/mgmt/${id}`)
}

/**
 * 获取业务链路的查询变量列表
 */
export function getTraceVariables(id) {
  return request.get(`/traces/query/${id}/variables`)
}

/**
 * 获取节点的子节点列表
 */
export function getChildNodes(nodeId) {
  return request.get(`/traces/query/nodes/${nodeId}/children`)
}

/**
 * 创建业务分类（字典值）
 */
export function createCategory(value) {
  return request.post('/traces/mgmt/categories/add', null, { params: { value } })
}

/**
 * 获取所有业务分类（从字典获取，返回字符串列表）
 */
export function getAllCategories() {
  return request.get('/traces/query/categories')
}

/**
 * 删除业务分类（字典值）
 */
export function deleteCategory(value) {
  return request.delete('/traces/mgmt/categories/delete', { params: { value } })
}

