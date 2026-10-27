import request from '@/utils/request'

/** 分页查询事故调查记录 */
export function getIncidentList(params) {
  return request.get('/incident-investigations', { params })
}

/** 调查记录详情（含证据） */
export function getIncidentDetail(id) {
  return request.get(`/incident-investigations/${id}`)
}

/** 重试失败调查 */
export function retryIncident(id) {
  return request.post(`/incident-investigations/${id}/retry`)
}
