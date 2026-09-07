import request from '@/utils/request.js'

/** 列出所有钉钉机器人 */
export function getRobotList() {
  return request.get('/dingtalk-robot/list')
}

/** 列出启用的机器人（供告警规则多选） */
export function getEnabledRobots() {
  return request.get('/dingtalk-robot/list-enabled')
}

/** 机器人详情 */
export function getRobotDetail(id) {
  return request.get(`/dingtalk-robot/${id}`)
}

/** 新增机器人 */
export function createRobot(data) {
  return request.post('/dingtalk-robot', data)
}

/** 更新机器人 */
export function updateRobot(id, data) {
  return request.put(`/dingtalk-robot/${id}`, data)
}

/** 删除机器人 */
export function deleteRobot(id) {
  return request.delete(`/dingtalk-robot/${id}`)
}

/** 启用机器人 */
export function enableRobot(id) {
  return request.put(`/dingtalk-robot/${id}/enable`)
}

/** 禁用机器人 */
export function disableRobot(id) {
  return request.put(`/dingtalk-robot/${id}/disable`)
}

/** 测试发送消息到该机器人对应的群 */
export function testSendRobot(id) {
  return request.post(`/dingtalk-robot/test/${id}`)
}

/** 查告警规则绑定的机器人 */
export function getRobotsByAlertConfig(configId) {
  return request.get(`/dingtalk-robot/by-alert-config/${configId}`)
}
