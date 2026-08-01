import request from '@/utils/request'

/**
 * 钉钉登录
 * @param {string} code - 钉钉授权码
 * @returns {Promise} 登录响应（包含token和用户信息）
 */
export function dingTalkLogin(code) {
  return request.post('/auth/dingtalk/login', null, {
    params: { code }
  }).then(res => res.data)
}


