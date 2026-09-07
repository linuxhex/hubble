import request from '@/utils/request'

/**
 * 执行日志查询（SSE流式返回）
 * 注意：由于EventSource只支持GET请求，这里使用fetch + ReadableStream实现
 */
export function queryLogsStream(
  data,
  onMessage,
  onError,
  onComplete
) {
  const controller = new AbortController()
  const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'
  
  // 获取Token
  const urlParams = new URLSearchParams(window.location.search)
  const tokenFromStorage = localStorage.getItem('auth_token')
  const tokenFromUrl = urlParams.get('token')
  const tokenFromCookie = getCookie('token')
  const token = tokenFromStorage || tokenFromUrl || tokenFromCookie
  
  fetch(`${baseURL}/traces/query/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(data),
    signal: controller.signal
  })
    .then(async (response) => {
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`)
      }
      
      const reader = response.body?.getReader()
      const decoder = new TextDecoder()
      
      if (!reader) {
        throw new Error('无法读取响应流')
      }
      
      let buffer = ''
      
      while (true) {
        const { done, value } = await reader.read()
        
        if (done) {
          break
        }
        
        buffer += decoder.decode(value, { stream: true })
        // SSE事件之间用两个换行符分隔
        const events = buffer.split('\n\n')
        buffer = events.pop() || '' // 保留最后一个不完整的事件
        
        for (const eventText of events) {
          if (!eventText.trim()) continue
          
          let eventType = ''
          let eventData = ''
          
          // 解析SSE事件（可能包含多行：event: xxx, data: xxx）
          const eventLines = eventText.split('\n')
          for (const line of eventLines) {
            const trimmedLine = line.trim()
            if (trimmedLine.startsWith('event:')) {
              eventType = trimmedLine.substring(6).trim()
            } else if (trimmedLine.startsWith('data:')) {
              // 如果已经有data，说明是多行data，需要拼接
              if (eventData) {
                eventData += '\n' + trimmedLine.substring(5).trim()
              } else {
                eventData = trimmedLine.substring(5).trim()
              }
            }
          }
          
          // 处理事件
          if (eventType === 'data' && eventData) {
            try {
              const result = JSON.parse(eventData)
              console.log('收到SSE数据:', result)
              onMessage?.(result)
            } catch (error) {
              console.error('解析SSE数据失败:', error, '原始数据:', eventData)
            }
          } else if (eventType === 'complete') {
            console.log('SSE流完成')
            onComplete?.()
          } else if (eventType === 'error') {
            try {
              const errorData = eventData ? JSON.parse(eventData) : {}
              console.error('SSE错误:', errorData)
              onError?.(new Error(errorData.message || '查询失败'))
            } catch {
              onError?.(new Error('查询失败'))
            }
          } else if (!eventType && eventData) {
            // 如果没有event类型但有data，也尝试解析（兼容某些SSE格式）
            try {
              const result = JSON.parse(eventData)
              console.log('收到SSE数据(无event类型):', result)
              onMessage?.(result)
            } catch (error) {
              console.error('解析SSE数据失败:', error, '原始数据:', eventData)
            }
          } else {
            // 调试：打印未处理的事件
            console.log('未处理的SSE事件:', { eventType, eventData, eventText })
          }
        }
      }
    })
    .catch((error) => {
      if (error.name !== 'AbortError') {
        console.error('查询失败:', error)
        onError?.(error)
      }
    })
  
  return controller
}

// 获取Cookie
function getCookie(name) {
  const value = `; ${document.cookie}`
  const parts = value.split(`; ${name}=`)
  if (parts.length === 2) return parts.pop()?.split(';').shift() || null
  return null
}

/**
 * 执行日志查询（POST方式，非流式）
 */
export function queryLogs(data) {
  return request.post('/traces/query', data)
}

/**
 * 导出查询结果
 */
export function exportQueryResults(data) {
  return request.post('/traces/query/export', data, {
    responseType: 'blob'
  })
}

/**
 * 查询子节点日志（SSE流式返回）
 */
export function queryChildNodesStream(
  nodeId,
  data,
  onMessage,
  onError,
  onComplete
) {
  const controller = new AbortController()
  const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'
  
  // 获取Token
  const urlParams = new URLSearchParams(window.location.search)
  const tokenFromStorage = localStorage.getItem('auth_token')
  const tokenFromUrl = urlParams.get('token')
  const tokenFromCookie = getCookie('token')
  const token = tokenFromStorage || tokenFromUrl || tokenFromCookie
  
  fetch(`${baseURL}/traces/query/nodes/${nodeId}/children/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(data),
    signal: controller.signal
  })
    .then(async (response) => {
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`)
      }
      
      const reader = response.body?.getReader()
      const decoder = new TextDecoder()
      
      if (!reader) {
        throw new Error('无法读取响应流')
      }
      
      let buffer = ''
      
      while (true) {
        const { done, value } = await reader.read()
        
        if (done) {
          break
        }
        
        buffer += decoder.decode(value, { stream: true })
        const events = buffer.split('\n\n')
        buffer = events.pop() || ''
        
        for (const eventText of events) {
          if (!eventText.trim()) continue
          
          let eventType = ''
          let eventData = ''
          
          const eventLines = eventText.split('\n')
          for (const line of eventLines) {
            const trimmedLine = line.trim()
            if (trimmedLine.startsWith('event:')) {
              eventType = trimmedLine.substring(6).trim()
            } else if (trimmedLine.startsWith('data:')) {
              if (eventData) {
                eventData += '\n' + trimmedLine.substring(5).trim()
              } else {
                eventData = trimmedLine.substring(5).trim()
              }
            }
          }
          
          if (eventType === 'data' && eventData) {
            try {
              const result = JSON.parse(eventData)
              onMessage?.(result)
            } catch (error) {
              console.error('解析SSE数据失败:', error)
            }
          } else if (eventType === 'complete') {
            onComplete?.()
          } else if (eventType === 'error') {
            try {
              const errorData = eventData ? JSON.parse(eventData) : {}
              onError?.(new Error(errorData.message || '查询失败'))
            } catch {
              onError?.(new Error('查询失败'))
            }
          }
        }
      }
    })
    .catch((error) => {
      if (error.name !== 'AbortError') {
        onError?.(error)
      }
    })
  
  return controller
}


