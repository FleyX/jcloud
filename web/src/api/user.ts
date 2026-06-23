import { del, get, post, put } from './request'
import type {
  BatchUserStatusDto,
  PageResult,
  UserPageQuery,
  UserSaveDto,
  UserStatusDto,
  UserUpdateDto,
  UserUpdateRolesDto,
  UserVo,
} from '@/types/auth'

export function fetchUserPage(params: UserPageQuery): Promise<PageResult<UserVo>> {
  return get<PageResult<UserVo>>('/users', params as Record<string, unknown>)
}

export function createUser(dto: UserSaveDto): Promise<UserVo> {
  return post<UserVo>('/users', dto)
}

export function updateUser(userId: string, dto: UserUpdateDto): Promise<UserVo> {
  return put<UserVo>(`/users/${userId}`, dto)
}

export function updateUserRoles(userId: string, roleIds: string[]): Promise<void> {
  const body: UserUpdateRolesDto = { userId, roleIds }
  return put<void>(`/users/${userId}/roles`, body)
}

export function updateUserStatus(userId: string, status: number): Promise<void> {
  const body: UserStatusDto = { userId, status }
  return put<void>(`/users/${userId}/status`, body)
}

export function batchUpdateUserStatus(dto: BatchUserStatusDto): Promise<string[]> {
  return put<string[]>('/users/batch/status', dto)
}

export function deleteUser(userId: string): Promise<boolean> {
  return del<boolean>(`/users/${userId}`)
}

export function batchDeleteUser(userIds: string[]): Promise<string[]> {
  return del<string[]>('/users/batch', userIds)
}
