import { BASE_URL, del, get, post } from './request'
import type { DeviceSessionVo, LoginVo, UserVo } from '@/types/auth'

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

/**
 * 获取当前用户登录设备会话列表；传 deviceId 用于标记当前设备（走统一请求封装，401 自动静默刷新）。
 */
export function listDevices(deviceId: string): Promise<DeviceSessionVo[]> {
  return get<DeviceSessionVo[]>('/auth/devices', { deviceId })
}

/**
 * 踢出指定设备会话（走统一请求封装，401 自动静默刷新）。
 */
export function revokeDevice(deviceId: string): Promise<void> {
  return del<void>(`/auth/devices/${deviceId}`)
}
