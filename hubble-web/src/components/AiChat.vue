<script setup>
import { ref, nextTick, onUnmounted, computed } from 'vue'
import { ChatDotRound, Close, Delete, Promotion } from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { ElMessage } from 'element-plus'

marked.setOptions({
  breaks: true,
  gfm: true
})

const isOpen = ref(false)
const inputText = ref('')
const messages = ref([])
const isThinking = ref(false)
const messagesContainer = ref(null)

let ws = null
let currentAssistantMsg = null

const wsUrl = computed(() => {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = window.location.host
  const token = localStorage.getItem('auth_token') || ''
  return `${protocol}//${host}/api/ws/ai/chat?token=${encodeURIComponent(token)}`
})

const wsConnected = ref(false)
let reconnectTimer = null

function connectWs() {
  if (ws && ws.readyState === WebSocket.OPEN) return

  const token = localStorage.getItem('auth_token')
  if (!token) {
    ElMessage.warning('登录已过期，请重新登录后再使用 AI 对话')
    return
  }

  ws = new WebSocket(wsUrl.value)

  ws.onopen = () => {
    console.log('AI Chat WS 已连接')
    wsConnected.value = true
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
  }

  ws.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data)
      handleWsMessage(data)
    } catch (e) {
      console.error('WS 消息解析失败:', e)
    }
  }

  ws.onerror = (err) => {
    console.error('WS 错误:', err)
    isThinking.value = false
  }

  ws.onclose = (event) => {
    console.log('AI Chat WS 已断开', event.code, event.reason)
    wsConnected.value = false
    isThinking.value = false
    ws = null
    if (event.code === 1008 || event.reason?.includes('认证')) {
      ElMessage.error('认证失败，请重新登录后再试')
    }
  }
}

function handleWsMessage(data) {
  switch (data.type) {
    case 'chunk':
      if (currentAssistantMsg) {
        currentAssistantMsg.content += data.content
        scrollToBottom()
      }
      break
    case 'done':
      isThinking.value = false
      currentAssistantMsg = null
      break
    case 'error':
      isThinking.value = false
      if (currentAssistantMsg && !currentAssistantMsg.content) {
        const idx = messages.value.indexOf(currentAssistantMsg)
        if (idx >= 0) {
          messages.value[idx].content = '抱歉，AI 服务暂时不可用：' + (data.message || '响应异常') + '。请稍后重试。'
        }
      }
      currentAssistantMsg = null
      if (data.message !== 'thinking') {
        ElMessage.error(data.message || 'AI 响应异常')
      }
      break
    case 'status':
      if (data.message === 'thinking') {
        isThinking.value = true
      }
      break
  }
}

function sendMessage() {
  const text = inputText.value.trim()
  if (!text || isThinking.value) return

  const token = localStorage.getItem('auth_token')
  if (!token) {
    ElMessage.warning('登录已过期，请刷新页面重新登录')
    return
  }

  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  scrollToBottom()

  if (!ws || ws.readyState !== WebSocket.OPEN) {
    connectWs()
    let waited = 0
    const checkConn = setInterval(() => {
      waited += 200
      if (ws && ws.readyState === WebSocket.OPEN) {
        clearInterval(checkConn)
        doSend(text)
      } else if (waited >= 3000) {
        clearInterval(checkConn)
        ElMessage.error('AI 连接失败，请检查网络或重新登录')
        isThinking.value = false
      }
    }, 200)
  } else {
    doSend(text)
  }
}

function doSend(text) {
  currentAssistantMsg = { role: 'assistant', content: '' }
  messages.value.push(currentAssistantMsg)
  isThinking.value = true
  scrollToBottom()

  ws.send(JSON.stringify({ action: 'chat', message: text }))
}

function clearChat() {
  messages.value = []
  currentAssistantMsg = null
  isThinking.value = false
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ action: 'clear' }))
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
}

function renderMarkdown(text) {
  if (!text) return ''
  try {
    const html = marked.parse(text)
    return DOMPurify.sanitize(html)
  } catch {
    return text
  }
}

function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

function toggleChat() {
  isOpen.value = !isOpen.value
  if (isOpen.value && !ws) {
    connectWs()
  }
}

onUnmounted(() => {
  if (ws) {
    ws.close()
    ws = null
  }
})

const quickQuestions = [
  '哪些接口劣化最严重？',
  '今天流量暴涨的接口有哪些？',
  'Redis 状态怎么样？',
  '哪些 Pod CPU 占用最高？'
]

function askQuick(q) {
  inputText.value = q
  sendMessage()
}
</script>

<template>
  <div class="ai-chat-container">
    <!-- 浮动球 -->
    <div class="ai-fab" :class="{ active: isOpen }" @click="toggleChat">
      <el-icon :size="24">
        <Close v-if="isOpen" />
        <ChatDotRound v-else />
      </el-icon>
    </div>

    <!-- 聊天面板 -->
    <transition name="slide-up">
      <div v-if="isOpen" class="ai-panel">
        <div class="ai-panel-header">
          <div class="ai-panel-title">
            <el-icon :size="18"><ChatDotRound /></el-icon>
            <span>Hubble 小助手</span>
            <span class="ws-status-dot" :class="{ connected: wsConnected }" :title="wsConnected ? '已连接' : '未连接'"></span>
          </div>
          <el-icon class="ai-panel-close" :size="16" @click="toggleChat"><Close /></el-icon>
        </div>

        <!-- 消息区 -->
        <div ref="messagesContainer" class="ai-messages">
          <div v-if="messages.length === 0" class="ai-welcome">
            <p>你好，我是 Hubble 小助手</p>
            <p class="ai-welcome-sub">可以问我监控相关的问题，比如：</p>
            <div class="ai-quick-questions">
              <el-tag
                v-for="q in quickQuestions"
                :key="q"
                class="ai-quick-tag"
                effect="plain"
                round
                @click="askQuick(q)"
              >
                {{ q }}
              </el-tag>
            </div>
          </div>

          <div
            v-for="(msg, idx) in messages"
            :key="idx"
            class="ai-msg"
            :class="msg.role"
          >
            <div class="ai-msg-bubble">
              <div
                v-if="msg.role === 'assistant'"
                class="ai-msg-markdown"
                v-html="renderMarkdown(msg.content)"
              />
              <div v-else class="ai-msg-text">{{ msg.content }}</div>
              <div v-if="msg.role === 'assistant' && idx === messages.length - 1 && isThinking && !msg.content" class="ai-typing">
                <span></span><span></span><span></span>
              </div>
            </div>
          </div>
        </div>

        <!-- 输入区 -->
        <div class="ai-input-area">
          <el-icon class="ai-clear-btn" :size="16" @click="clearChat" title="清空对话">
            <Delete />
          </el-icon>
          <textarea
            v-model="inputText"
            class="ai-input"
            placeholder="输入问题..."
            rows="1"
            @keydown="handleKeydown"
          />
          <el-icon
            class="ai-send-btn"
            :size="18"
            :class="{ disabled: !inputText.trim() || isThinking }"
            @click="sendMessage"
          >
            <Promotion />
          </el-icon>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.ai-chat-container {
  position: fixed;
  bottom: 24px;
  right: 24px;
  z-index: 9999;
}

.ai-fab {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background: linear-gradient(135deg, #409eff, #6366f1);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 4px 16px rgba(64, 158, 255, 0.4);
  transition: all 0.3s;
}

.ai-fab:hover {
  transform: scale(1.08);
  box-shadow: 0 6px 20px rgba(64, 158, 255, 0.5);
}

.ai-fab.active {
  background: linear-gradient(135deg, #909399, #606266);
}

.ai-panel {
  position: absolute;
  bottom: 64px;
  right: 0;
  width: 400px;
  height: 560px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.ai-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  background: linear-gradient(135deg, #409eff, #6366f1);
  color: #fff;
}

.ai-panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
}

.ai-panel-close {
  cursor: pointer;
  opacity: 0.8;
  transition: opacity 0.2s;
}

.ai-panel-close:hover {
  opacity: 1;
}

.ws-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.4);
  display: inline-block;
  margin-left: 6px;
  transition: background 0.3s;
}

.ws-status-dot.connected {
  background: #67c23a;
  box-shadow: 0 0 4px rgba(103, 194, 58, 0.6);
}

.ai-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ai-welcome {
  text-align: center;
  padding: 40px 16px;
  color: #666;
}

.ai-welcome p:first-child {
  font-size: 16px;
  font-weight: 600;
  color: #333;
  margin-bottom: 8px;
}

.ai-welcome-sub {
  font-size: 13px;
  margin-bottom: 16px;
}

.ai-quick-questions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.ai-quick-tag {
  cursor: pointer;
  transition: all 0.2s;
}

.ai-quick-tag:hover {
  color: #409eff;
  border-color: #409eff;
}

.ai-msg {
  display: flex;
}

.ai-msg.user {
  justify-content: flex-end;
}

.ai-msg.assistant {
  justify-content: flex-start;
}

.ai-msg-bubble {
  max-width: 85%;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

.ai-msg.user .ai-msg-bubble {
  background: #409eff;
  color: #fff;
  border-bottom-right-radius: 4px;
}

.ai-msg.assistant .ai-msg-bubble {
  background: #f4f4f5;
  color: #333;
  border-bottom-left-radius: 4px;
}

.ai-msg-markdown :deep(p) {
  margin: 0 0 8px;
}

.ai-msg-markdown :deep(p:last-child) {
  margin-bottom: 0;
}

.ai-msg-markdown :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 8px 0;
  font-size: 13px;
}

.ai-msg-markdown :deep(th),
.ai-msg-markdown :deep(td) {
  border: 1px solid #e4e7ed;
  padding: 4px 8px;
  text-align: left;
}

.ai-msg-markdown :deep(th) {
  background: #f5f7fa;
  font-weight: 600;
}

.ai-msg-markdown :deep(code) {
  background: #e8e8e8;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 13px;
}

.ai-msg-markdown :deep(pre) {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 10px;
  border-radius: 6px;
  overflow-x: auto;
  margin: 8px 0;
}

.ai-msg-markdown :deep(pre code) {
  background: none;
  padding: 0;
  color: inherit;
}

.ai-msg-markdown :deep(ul),
.ai-msg-markdown :deep(ol) {
  padding-left: 20px;
  margin: 4px 0;
}

.ai-typing {
  display: flex;
  gap: 4px;
  padding: 4px 0;
}

.ai-typing span {
  width: 6px;
  height: 6px;
  background: #909399;
  border-radius: 50%;
  animation: typing-bounce 1.4s infinite ease-in-out both;
}

.ai-typing span:nth-child(1) { animation-delay: -0.32s; }
.ai-typing span:nth-child(2) { animation-delay: -0.16s; }

@keyframes typing-bounce {
  0%, 80%, 100% { transform: scale(0); }
  40% { transform: scale(1); }
}

.ai-input-area {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid #ebeef5;
  background: #fafafa;
}

.ai-clear-btn {
  cursor: pointer;
  color: #909399;
  transition: color 0.2s;
  flex-shrink: 0;
}

.ai-clear-btn:hover {
  color: #f56c6c;
}

.ai-input {
  flex: 1;
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 14px;
  resize: none;
  outline: none;
  font-family: inherit;
  line-height: 1.4;
  max-height: 80px;
  transition: border-color 0.2s;
}

.ai-input:focus {
  border-color: #409eff;
}

.ai-send-btn {
  cursor: pointer;
  color: #409eff;
  transition: all 0.2s;
  flex-shrink: 0;
}

.ai-send-btn:hover {
  transform: scale(1.1);
}

.ai-send-btn.disabled {
  color: #c0c4cc;
  cursor: not-allowed;
  transform: none;
}

.slide-up-enter-active,
.slide-up-leave-active {
  transition: all 0.3s ease;
}

.slide-up-enter-from,
.slide-up-leave-to {
  opacity: 0;
  transform: translateY(20px);
}

.ai-messages::-webkit-scrollbar {
  width: 4px;
}

.ai-messages::-webkit-scrollbar-thumb {
  background: #dcdfe6;
  border-radius: 2px;
}
</style>
