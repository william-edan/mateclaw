/**
 * 认证工具函数
 * 统一处理 token 失效跳转和自动续期
 */

let isRedirecting = false

/**
 * Clear all per-user identity from localStorage. Shared by the in-page logout
 * and the 401 handler so a second user on the same (desktop) instance never
 * inherits the first user's workspace id / capabilities.
 *
 * <p>Intentionally does NOT touch the Pinia workspace store — importing it here
 * would create a cycle (auth → store → api → auth). Callers that stay in-page
 * (no full reload) must call {@code useWorkspaceStore().reset()} themselves.
 */
export function clearSession() {
  ;['token', 'username', 'role', 'userId', 'mc-user-id', 'mc-workspace-id'].forEach((key) =>
    localStorage.removeItem(key),
  )
}

/**
 * 处理认证失败：清除 token 并跳转登录页
 * 使用 isRedirecting 标记防止多个并发请求同时触发跳转
 */
export function handleAuthFailure() {
  clearSession()
  // 已经在登录页则不再跳转，避免死循环
  if (window.location.pathname === '/login') {
    return
  }
  if (!isRedirecting) {
    isRedirecting = true
    window.location.href = '/login'
  }
}

/**
 * 从响应头中提取新 token 并更新 localStorage
 * 支持 fetch Headers 和 Axios headers（对象格式）
 */
export function updateTokenFromHeader(headers: any) {
  if (!headers) return
  const newToken =
    typeof headers.get === 'function'
      ? headers.get('x-new-token')
      : headers['x-new-token']
  if (newToken && typeof newToken === 'string') {
    localStorage.setItem('token', newToken)
  }
}
