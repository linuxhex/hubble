/**
 * 健康度评估 util：只标“告警（红）”和“预警（黄）”，正常不染色。
 * 规则与后端 HealthEvaluator 保持一致。
 */

// 健康等级（只红黄染色，正常 NORMAL 不加色）
export const HEALTH_STATUS = {
  RED: 'RED',
  YELLOW: 'YELLOW',
  NORMAL: 'NORMAL'
}

// 等级 → Element Plus tag type（正常用 info 灰，不搞绿色）
export const STATUS_TAG_TYPE = {
  RED: 'danger',
  YELLOW: 'warning',
  NORMAL: 'info'
}

// 等级 → 数值 class（正常为空，沿用默认色）
export const STATUS_VALUE_CLASS = {
  RED: 'stat-value-danger',
  YELLOW: 'stat-value-warning',
  NORMAL: ''
}

// 等级 → 颜色
export const STATUS_COLOR = {
  RED: '#F56C6C',
  YELLOW: '#E6A23C',
  NORMAL: '#333'
}

// 等级 → 中文标签
export const STATUS_LABEL = {
  RED: '告警',
  YELLOW: '预警',
  NORMAL: '正常'
}

/**
 * 根据当前值与阈值评估健康等级
 * @param {number} value 当前值
 * @param {number} threshold 阈值
 * @returns {string} HEALTH_STATUS
 */
export function getHealthStatus(value, threshold) {
  if (threshold == null || threshold <= 0 || value == null) return HEALTH_STATUS.NORMAL
  if (value >= threshold) return HEALTH_STATUS.RED
  if (value >= threshold * 0.5) return HEALTH_STATUS.YELLOW
  return HEALTH_STATUS.NORMAL
}
