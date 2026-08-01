/**
 * 时间戳工具函数
 */

/**
 * 确保时间戳是秒级的
 * @param {number|string} timestamp - 时间戳（可能是秒级或毫秒级）
 * @returns {number} 秒级时间戳
 */
export function ensureSecondTimestamp(timestamp) {
  const num = typeof timestamp === 'string' ? Number(timestamp) : timestamp
  
  if (isNaN(num) || !isFinite(num)) {
    console.error('无效的时间戳:', timestamp)
    return Math.floor(Date.now() / 1000)
  }
  
  if (num > 10000000000) {
    return Math.floor(num / 1000)
  }
  
  return num
}

/**
 * 确保时间戳是毫秒级的
 * @param {number|string} timestamp - 时间戳（可能是秒级或毫秒级）
 * @returns {number} 毫秒级时间戳
 */
export function ensureMillisecondTimestamp(timestamp) {
  const num = typeof timestamp === 'string' ? Number(timestamp) : timestamp
  
  if (isNaN(num) || !isFinite(num)) {
    console.error('无效的时间戳:', timestamp)
    return Date.now()
  }
  
  if (num <= 10000000000) {
    return num * 1000
  }
  
  return num
}

/**
 * 获取当前秒级时间戳
 * @returns {number} 当前秒级Unix时间戳
 */
export function nowInSeconds() {
  return Math.floor(Date.now() / 1000)
}


