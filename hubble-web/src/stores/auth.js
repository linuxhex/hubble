import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { dingTalkLogin } from '@/api/auth'

const TOKEN_KEY = 'auth_token'
const USER_KEY = 'auth_user'

export const useAuthStore = defineStore('auth', () => {
  // 状态
  const token = ref(localStorage.getItem(TOKEN_KEY))
  const user = ref(null)

  // 计算属性
  const isLoggedIn = computed(() => !!token.value && !!user.value)

  // 登录
  async function login(code) {
    try {
      const response = await dingTalkLogin(code)
      token.value = response.token
      user.value = response.user
      localStorage.setItem(TOKEN_KEY, response.token)
      localStorage.setItem(USER_KEY, JSON.stringify(response.user))
      return true
    } catch (error) {
      console.error('登录失败:', error)
      throw error
    }
  }

  // 退出登录
  function logout() {
    token.value = null
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
    // 刷新页面
    window.location.reload()
  }

  // 初始化（从localStorage读取）
  function init() {
    const storedToken = localStorage.getItem(TOKEN_KEY)
    const storedUser = localStorage.getItem(USER_KEY)
    
    if (storedToken && storedUser) {
      token.value = storedToken
      try {
        user.value = JSON.parse(storedUser)
      } catch (e) {
        // 如果解析失败，清除无效数据
        logout()
      }
    }
  }

  return {
    token,
    user,
    isLoggedIn,
    login,
    logout,
    init
  }
})


