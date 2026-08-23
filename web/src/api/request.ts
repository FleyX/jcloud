import { useNotificationStore } from '@/store/notification'
import { useUserStore } from '@/store/user'
import router, { isGuardInFlight } from '@/router'
import { UnauthorizedError } from '@/api/errors'
import type { ApiResponse, TokenPairVo } from '@/types/auth'

export const BASE_URL = '/jcloud/api'

/** access 剩余有效期低于该阈值时先刷新再发业务请求 */
const REFRESH_THRESHOLD_MS = 10 * 60 * 1000

/** 豁免预检刷新的精确路径（刷新接口豁免保证无递归；登录/注册/登出走各自原生流程） */
const REFRESH_EXEMPT_EXACT = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/logout']

/** 单飞刷新承诺：并发业务请求共享同一次刷新 */
let refreshPromise: Promise<void> | null = null

/**
 * 是否豁免预检刷新：精确匹配登录/注册/刷新/登出，前缀匹配公开分享 /s/。
 * /auth/me、/auth/devices、/auth/logout-all 不豁免（登录态接口同样需要有效 access；
 * 页面刷新后守卫的首个 /auth/me 依赖预检兜底刷新）。
 */
function isRefreshExempt(url: string): boolean {
  return REFRESH_EXEMPT_EXACT.includes(url) || url.startsWith('/s/')
}

/**
 * 预检 access 有效期：剩余充足直接放行；否则单飞调用刷新接口换取新令牌对
 * （Set-Cookie 下发，前端仅记录新过期时间到内存）。失败沿调用链上抛，不额外兜底。
 */
async function ensureFreshAccessToken(): Promise<void> {
  const userStore = useUserStore()
  if (userStore.accessExpiresAt !== null && userStore.accessExpiresAt - Date.now() > REFRESH_THRESHOLD_MS) {
    return
  }
  if (!refreshPromise) {
    refreshPromise = post<TokenPairVo>('/auth/refresh')
      .then((data) => {
        userStore.accessExpiresAt = Number(data.accessExpiresAt)
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

async function handleResponse<T>(response: Response): Promise<T> {
  const json = (await response.json()) as ApiResponse<T>
  if (json.code === 401) {
    // 续期由前端请求前预检触发（见 ensureFreshAccessToken）；401 直接清登录态并跳登录页，不刷新不重试。
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
  // 预检刷新：非豁免路径先确保 access 剩余有效期充足（临期/未知时先单飞刷新）
  if (!isRefreshExempt(url)) {
    await ensureFreshAccessToken()
  }
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
