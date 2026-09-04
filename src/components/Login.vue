<script setup>
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const loading = ref(false)

onMounted(async () => {
  const code = route.query.code
  if (code) {
    loading.value = true
    try {
      const success = await authStore.login(code)
      if (success) {
        const redirect = route.query.redirect || '/gateway'
        router.replace(redirect)
      }
    } catch (err) {
      ElMessage.error('登录失败：' + (err.message || '未知错误'))
    } finally {
      loading.value = false
    }
  }
})

function handleDingTalkLogin() {
  const appId = import.meta.env.VITE_DINGTALK_APP_ID || import.meta.env.VITE_DINGTALK_APP_KEY || ''
  const redirectUri = encodeURIComponent(window.location.origin + '/login')
  const oauthUrl = `https://login.dingtalk.com/oauth2/auth?client_id=${appId}&redirect_uri=${redirectUri}&response_type=code&scope=openid&prompt=consent`
  window.location.href = oauthUrl
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <img src="@/assets/hubble-logo.svg" alt="Hubble" class="login-logo" />
        <h1>Hubble 监控大盘</h1>
        <p class="login-subtitle">服务监控 · 链路追踪 · 异常告警</p>
      </div>

      <div class="login-body">
        <el-button
          class="dingtalk-login-btn"
          :loading="loading"
          size="large"
          @click="handleDingTalkLogin"
        >
          <svg v-if="!loading" class="dingtalk-icon" viewBox="0 0 1024 1024" width="20" height="20">
            <path fill="#fff" d="M512 0C229.2 0 0 229.2 0 512s229.2 512 512 512 512-229.2 512-512S794.8 0 512 0z m236.5 703.2c-8.4 12.5-27.6 21.3-42.3 15.1-48.5-20.5-101.3-55.3-146.2-96.5-78.5 42.3-170.7 72.3-245.4 55.3-12.5-2.8-19.6-15.4-12.5-26.5 7-11.1 21.3-15.4 33.8-12.5 55.3 12.5 133.8-5.6 205-42.3-35.4-38.2-65.3-82-85.1-126.9-5.6-12.5 2.8-26.5 16.8-27.9 13.9-1.4 25 8.4 29.2 20.9 18.2 51.1 52.5 99 93.4 138 42.3-26.5 79.9-58.1 107.8-93.4H498.5c-15.4 0-27.9-12.5-27.9-27.9s12.5-27.9 27.9-27.9h250.8c15.4 0 27.9 12.5 27.9 27.9 0 72.3-38.2 139.4-96.2 193.3z"/>
          </svg>
          {{ loading ? '登录中...' : '钉钉扫码登录' }}
        </el-button>
        <p class="login-hint">请使用钉钉扫码或点击登录</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  width: 100vw;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #001529 0%, #003a70 50%, #0050b3 100%);
}

.login-card {
  width: 420px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.2);
  overflow: hidden;
}

.login-header {
  text-align: center;
  padding: 40px 40px 20px;
  background: linear-gradient(135deg, #001529, #003a70);
  color: #fff;
}

.login-logo {
  height: 48px;
  margin-bottom: 16px;
}

.login-header h1 {
  font-size: 24px;
  font-weight: 600;
  margin: 0 0 8px;
}

.login-subtitle {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.7);
  margin: 0;
}

.login-body {
  padding: 40px;
  text-align: center;
}

.dingtalk-login-btn {
  width: 100%;
  height: 48px;
  font-size: 16px;
  background: #3296fa;
  border-color: #3296fa;
  color: #fff;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.dingtalk-login-btn:hover {
  background: #1677ff;
  border-color: #1677ff;
  color: #fff;
}

.dingtalk-icon {
  flex-shrink: 0;
}

.login-hint {
  margin-top: 16px;
  font-size: 13px;
  color: #999;
}
</style>
