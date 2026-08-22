import { useNotificationStore } from '@/store/notification'
import { useUserStore } from '@/store/user'
import router, { isGuardInFlight } from '@/router'
import { UnauthorizedError } from '@/api/errors'
import type { ApiResponse } from '@/types/auth'

export const BASE_URL = '/jcloud/api'

async function handleResponse<T>(response: Response): Promise<T> {
  const json = (await response.json()) as ApiResponse<T>
  if (json.code === 401) {
    // 登录态经 cookie 承载，静默续期由后端过滤器接管；401 直接清登录态并跳登录页。
    // 守卫进行中不主动跳转：由守卫通过重定向完成（守卫内的 push 会与当前导航竞态，导致首跳异常终止、应用无法挂载白屏）。
    const userStore = useUserStore()
    userStore.logoutAction()
    if (!isGuardInFlight()) {
      router.push('/login')
    }
    throw new UnauthorizedError(json.msg || '登录已过期')
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
 * 内部统一请求入口。同源请求带 credentials 自动携带 cookie 鉴权，不再设置 Authorization 头。
 */
async function request<T>(
  method: string,
  url: string,
  body?: unknown,
  params?: Record<string, unknown>,
): Promise<T> {
  const response = await fetch(buildUrl(url, params), {
    method,
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return handleResponse<T>(response)
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
