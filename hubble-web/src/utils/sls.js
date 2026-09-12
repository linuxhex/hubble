import { ensureSecondTimestamp } from './timestamp'

/**
 * 生成 SLS 控制台跳转链接
 * @param {string} traceId - trace ID（当 queryString 未提供时使用）
 * @param {Object} options - 选项（项目名称、logstore名称、时间范围、查询字符串等）
 * @returns {string} SLS 控制台链接
 */
export function generateSlsLink(traceId, options = {}) {
  // 默认项目名称（从配置或环境变量获取）
  const slsProject = options?.project || import.meta.env?.VITE_SLS_PROJECT || ''
  
  // 使用具体的 logstore，如果没有提供则使用 'all'
  const logstore = options?.logstore || 'all'
  
  // 构建查询字符串：优先使用 options.queryString，否则使用 traceId
  let queryString
  if (options?.queryString) {
    queryString = options.queryString.trim()
  } else {
    if (!traceId || traceId.trim() === '') {
      return ''
    }
    queryString = `trace: ${traceId.trim()}`
  }
  
  // Base64 编码（支持 Unicode 字符）
  const encodedQuery = btoa(unescape(encodeURIComponent(queryString)))
  
  // 构建基础链接，使用具体的 logstore
  let slsLink = `https://sls.console.aliyun.com/lognext/project/${slsProject}/logsearch/${logstore}?encode=base64&queryString=${encodedQuery}`
  
  // 添加时间范围参数
  if (options?.startTime && options?.endTime) {
    // 确保时间戳是秒级的
    const startTimeSec = ensureSecondTimestamp(options.startTime)
    const endTimeSec = ensureSecondTimestamp(options.endTime)
    // queryTimeType=99 表示自定义时间范围，SLS控制台使用秒级时间戳
    slsLink += `&queryTimeType=99&startTime=${startTimeSec}&endTime=${endTimeSec}`
  }
  
  return slsLink
}

/**
 * 在新标签页打开 SLS 控制台
 * @param {string} traceId - trace ID
 * @param {Object} options - 选项（项目名称、时间范围等）
 */
export function openSlsConsole(traceId, options = {}) {
  const link = generateSlsLink(traceId, options)
  if (link) {
    window.open(link, '_blank')
  }
}


