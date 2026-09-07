import request from '@/utils/request.js'

/**
 * 创建SLS关键字模版
 */
export function createSlsKeyword(data) {
  return request.post('/sls-keywords/mgmt', data)
}

/**
 * 获取SLS关键字模版列表
 */
export function getSlsKeywordList(params) {
  return request.get('/sls-keywords/query', { params })
}

/**
 * 获取SLS关键字模版详情
 */
export function getSlsKeywordDetail(id) {
  return request.get(`/sls-keywords/query/${id}`)
}

/**
 * 更新SLS关键字模版
 */
export function updateSlsKeyword(id, data) {
  return request.put(`/sls-keywords/mgmt/${id}`, data)
}

/**
 * 删除SLS关键字模版
 */
export function deleteSlsKeyword(id) {
  return request.delete(`/sls-keywords/mgmt/${id}`)
}

/**
 * 获取应用列表
 */
export function getApplicationList() {
  return request.get('/sls-keywords/query/applications')
}

/**
 * 获取标签列表
 */
export function getTagList() {
  return request.get('/sls-keywords/query/tags')
}

/**
 * 新增应用
 */
export function addApplication(value) {
  return request.post('/sls-keywords/mgmt/applications/add', { value })
}

/**
 * 新增标签
 */
export function addTag(value) {
  return request.post('/sls-keywords/mgmt/tags/add', { value })
}
