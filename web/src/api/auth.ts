import { get, post } from './request'
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
