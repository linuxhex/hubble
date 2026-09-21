// 最近查询记录（localStorage 持久化，最多保留 10 条，去重置顶）
const MAX_ITEMS = 10

export function loadRecentSearches(key) {
  try {
    const raw = localStorage.getItem(key)
    const list = raw ? JSON.parse(raw) : []
    return Array.isArray(list) ? list : []
  } catch (e) {
    return []
  }
}

export function pushRecentSearch(key, entry) {
  if (!entry || !entry.text) return
  const list = loadRecentSearches(key).filter((item) => item.text !== entry.text)
  list.unshift({ ...entry, time: Date.now() })
  try {
    localStorage.setItem(key, JSON.stringify(list.slice(0, MAX_ITEMS)))
  } catch (e) {
    // localStorage 不可用时静默降级
  }
}

export function clearRecentSearches(key) {
  try {
    localStorage.removeItem(key)
  } catch (e) {
    // ignore
  }
}
