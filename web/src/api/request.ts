import { useNotificationStore } from '@/store/notification'
import { useUserStore } from '@/store/user'
import router from '@/router'
import type { ApiResponse, TokenPairVo } from '@/types/auth'

const BASE_URL = '/jcloud/api'

/**
 * 模块级单飞：进行中的刷新令牌请求。并发请求同时遇 401 时只发起一次刷新，其余请求等待同一 Promise。
 */
let refreshing: Promise<boolean> | null = null

/**
 * 用刷新令牌静默换取新的访问令牌与刷新令牌。
 * 刷新本身走裸 fetch（不经封装，避免与 401 重试逻辑形成递归）。
 * 成功（code===200）应用新令牌对并返回 true；任何失败（网络错误、code!==200、无刷新令牌）返回 false。
 */
async function silentRefresh(): Promise<boolean> {
  if (refreshing) {
    return refreshing
  }
  const userStore = useUserStore()
  if (!userStore.refreshToken) {
    return false
  }
  refreshing = (async () => {
    try {
      const response = await fetch(`${BASE_URL}/auth/refresh`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ refreshToken: userStore.refreshToken }),
      })
      const json = (await response.json()) as ApiResponse<TokenPairVo>
      if (json.code !== 200) {
        return false
      }
      useUserStore().applyTokenPair(json.data)
      return true
    } catch {
      return false
    } finally {
      refreshing = null
    }
  })()
  return refreshing
}

async function handleResponse<T>(
  response: Response,
  method: string,
  url: string,
  body?: unknown,
  params?: Record<string, unknown>,
  isRetry = false,
): Promise<T> {
  const json = (await response.json()) as ApiResponse<T>
  if (json.code === 401) {
    // 中间态 401 保持静默（不弹通知）；刷新走裸 fetch，不会经此分支
    const userStore = useUserStore()
    if (!isRetry) {
      const refreshed = await silentRefresh()
      if (refreshed) {
        // 换新成功，以最新 token 重发原请求一次；重发仍 401 则落入下方失败路径
        return request<T>(method, url, body, params, true)
      }
    }
    // 刷新失败或重发后仍 401：清除登录态并跳登录页（沿用现有 401 失败语义，不弹通知）
    userStore.logoutAction()
    router.push('/login')
    throw new Error(json.msg || '登录已过期')
  }
  if (json.code !== 200) {
    const message = json.msg || '请求失败'
    const notificationStore = useNotificationStore()
    notificationStore.error(message)
    throw new Error(message)
  }
  return json.data
}

/**
 * 内部统一请求入口。getHeaders() 在每次调用时读取最新 token，重发时自然带上新令牌。
 */
async function request<T>(
  method: string,
  url: string,
  body?: unknown,
  params?: Record<string, unknown>,
  isRetry = false,
): Promise<T> {
  const response = await fetch(buildUrl(url, params), {
    method,
    headers: getHeaders(),
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return handleResponse<T>(response, method, url, body, params, isRetry)
}

function getHeaders(): HeadersInit {
  const userStore = useUserStore()
  const headers: HeadersInit = {
    'Content-Type': 'application/json',
  }
  if (userStore.token) {
    headers.Authorization = `Bearer ${userStore.token}`
  }
  return headers
}

function buildQueryString(params?: Record<string, unknown>): string {
  if (!params) {
    return ''
  }
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      query.append(key, String(value))
    }
  })
  return query.toString()
}

function buildUrl(url: string, params?: Record<string, unknown>): string {
  let fullUrl = `${BASE_URL}${url}`
  const queryString = buildQueryString(params)
  if (queryString) {
    fullUrl += `?${queryString}`
  }
  return fullUrl
}

export async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  return request<T>('GET', url, undefined, params)
}

export async function post<T>(url: string, body?: unknown, params?: Record<string, unknown>): Promise<T> {
  return request<T>('POST', url, body, params)
}

export async function put<T>(url: string, body?: unknown, params?: Record<string, unknown>): Promise<T> {
  return request<T>('PUT', url, body, params)
}

export async function del<T>(url: string, body?: unknown, params?: Record<string, unknown>): Promise<T> {
  return request<T>('DELETE', url, body, params)
}

export async function patch<T>(url: string, body?: unknown, params?: Record<string, unknown>): Promise<T> {
  return request<T>('PATCH', url, body, params)
}
