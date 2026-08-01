import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * 成功提示
 */
export function showSuccess(message, options) {
  return ElMessage.success({
    message,
    duration: 3000,
    ...options
  })
}

/**
 * 错误提示
 */
export function showError(message, options) {
  return ElMessage.error({
    message,
    duration: 5000,
    ...options
  })
}

/**
 * 警告提示
 */
export function showWarning(message, options) {
  return ElMessage.warning({
    message,
    duration: 3000,
    ...options
  })
}

/**
 * 信息提示
 */
export function showInfo(message, options) {
  return ElMessage.info({
    message,
    duration: 3000,
    ...options
  })
}

/**
 * 确认对话框
 */
export function confirm(message, title = '提示', options) {
  return ElMessageBox.confirm(message, title, {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning',
    ...options
  }).then(() => {
    // 用户点击确定
  }).catch(() => {
    // 用户点击取消或关闭
    throw new Error('用户取消操作')
  })
}


