import { BASE_URL, get, post } from './request'
import type { LoginVo, UserVo } from '@/types/auth'

export interface LoginParams {
  username: string
  password: string
  deviceId: string
}

export interface RegisterParams {
  username: string
  password: string
  email?: string
  nickname?: string
}

export function login(params: LoginParams): Promise<LoginVo> {
  return post<LoginVo>('/auth/login', params)
}

export function register(params: RegisterParams): Promise<UserVo> {
  return post<UserVo>('/auth/register', params)
}

export function getCurrentUser(): Promise<LoginVo> {
  return get<LoginVo>('/auth/me')
}

/**
 * 登出当前设备：用裸 fetch 发送（与 request.ts 的 silentRefresh 同理）。
 * 访问令牌已过期时走封装会触发刷新/登出递归，因此不经过统一请求封装；失败静默（best-effort）。
 */
export async function logout(refreshToken: string): Promise<void> {
  try {
    await fetch(`${BASE_URL}/auth/logout`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ refreshToken }),
    })
  } catch {
    // best-effort：网络异常不影响本地清理
  }
}

/**
 * 登出全部设备：走统一请求封装（前端请求层会自动静默刷新后重试）。
 */
export function logoutAll(): Promise<void> {
  return post<void>('/auth/logout-all')
}
