/**
 * 大盘实时告警订阅（SSE）。
 * 用原生 EventSource 订阅 /alert-data/sse；token 走 url 参数（AuthFilter 支持 ?token=）。
 * EventSource 断开会自动重连，无需手动处理。
 */

const BASE = import.meta.env.VITE_API_BASE_URL || '/api'

/**
 * 订阅实时告警
 * @param {(payload: object) => void} onAlert 收到 alert 事件的回调
 * @returns {() => void} 关闭订阅的函数
 */
export function subscribeAlerts(onAlert) {
  const token = localStorage.getItem('auth_token') || ''
  const url = `${BASE}/alert-data/sse?token=${encodeURIComponent(token)}`
  const es = new EventSource(url)

  es.addEventListener('alert', (e) => {
    try {
      onAlert(JSON.parse(e.data))
    } catch (err) {
      // 解析失败忽略，保持订阅
    }
  })
  // onerror 时 EventSource 自动重连，这里不手动关闭
  return () => es.close()
}
