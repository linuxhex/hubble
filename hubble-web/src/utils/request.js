import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import router from '@/router'

// 创建axios实例
const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 防止并发401重复弹窗
let loginPromptShowing = false

// 请求拦截器
service.interceptors.request.use(
  (config) => {
    // 优先从localStorage读取Token，其次从URL参数，最后从Cookie
    const tokenFromStorage = localStorage.getItem('auth_token')
    const urlParams = new URLSearchParams(window.location.search)
    const tokenFromUrl = urlParams.get('token')
    const tokenFromCookie = getCookie('token')
    
    const token = tokenFromStorage || tokenFromUrl || tokenFromCookie
    
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// 响应拦截器
service.interceptors.response.use(
  (response) => {
    // 如果是blob响应（如导出功能），直接返回
    if (response.config.responseType === 'blob') {
      return response.data
    }
    
    const res = response.data
    
    // 如果返回的状态码不是200，则视为错误
    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    
    return res
  },
  (error) => {
    // 处理网络错误
    if (error.response) {
      // 服务器返回了错误状态码
      const status = error.response.status
      const message = error.response.data?.message || error.message
      
      switch (status) {
        case 401: {
          localStorage.removeItem('auth_token')
          localStorage.removeItem('auth_user')
          if (!loginPromptShowing) {
            loginPromptShowing = true
            ElMessageBox.confirm('登录状态已失效，是否重新登录？当前页面将保留。', '登录提示', {
              confirmButtonText: '重新登录',
              cancelButtonText: '留在本页',
              type: 'warning'
            }).then(() => {
              const current = router.currentRoute.value
              router.push({ path: '/login', query: current.path !== '/login' ? { redirect: current.fullPath } : {} })
            }).catch(() => {
              ElMessage.info('已取消登录，可继续浏览当前页面')
            }).finally(() => {
              loginPromptShowing = false
            })
          }
          break
        }
        case 403:
          ElMessage.error('没有权限访问该资源')
          break
        case 404:
          ElMessage.error('请求的资源不存在')
          break
        case 500:
          ElMessage.error('服务器内部错误，请稍后重试')
          break
        case 502:
        case 503:
        case 504:
          ElMessage.error('服务暂时不可用，请稍后重试')
          break
        default:
          ElMessage.error(message || `请求失败 (${status})`)
      }
    } else if (error.request) {
      // 请求已发出，但没有收到响应
      ElMessage.error('网络连接失败，请检查网络设置')
    } else {
      // 请求配置出错
      ElMessage.error(error.message || '请求配置错误')
    }
    
    return Promise.reject(error)
  }
)

// 获取Cookie
function getCookie(name) {
  const value = `; ${document.cookie}`
  const parts = value.split(`; ${name}=`)
  if (parts.length === 2) return parts.pop()?.split(';').shift() || null
  return null
}

export default service



