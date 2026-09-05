/**
 * 安全格式化工具，防止 null/undefined 调 .toFixed() 崩溃。
 * 后端返回 null 时前端显示 '--' 而非白屏。
 */

export const safeToFixed = (val, n = 1) => {
  if (val == null || isNaN(val)) return '--'
  return Number(val).toFixed(n)
}

export const safeRound = (val) => {
  if (val == null || isNaN(val)) return '--'
  return Math.round(val)
}

export const safePercent = (val, n = 1) => {
  return safeToFixed(val, n) + '%'
}

export const safeNumber = (val) => {
  if (val == null || isNaN(val)) return '--'
  return Number(val).toLocaleString()
}
