/**
 * 前端纯 CSV 导出工具，无需后端支持。
 * @param filename  文件名（不含扩展名）
 * @param rows      数据行数组
 * @param columns   列定义 [{label, prop}]，label 为表头，prop 为数据字段名
 */
export function exportCSV(filename, rows, columns) {
  if (!rows || rows.length === 0) {
    return false
  }
  const header = columns.map(c => `"${c.label}"`).join(',')
  const body = rows.map(r =>
    columns.map(c => {
      const val = r[c.prop]
      if (val == null) return '""'
      // 转义双引号和换行
      const str = String(val).replace(/"/g, '""').replace(/\n/g, ' ')
      return `"${str}"`
    }).join(',')
  ).join('\n')
  // BOM 头确保 Excel 正确识别 UTF-8
  const blob = new Blob(['﻿' + header + '\n' + body], { type: 'text/csv;charset=utf-8' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `${filename}_${new Date().toISOString().slice(0, 10)}.csv`
  link.click()
  URL.revokeObjectURL(link.href)
  return true
}
