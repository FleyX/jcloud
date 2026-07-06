import { del, get, post, put } from './request'
import type {
  BatchUserStatusDto,
  ChangePasswordDto,
  PageResult,
  UserPageQuery,
  UserProfileUpdateDto,
  UserProfileVo,
  UserSaveDto,
  UserStatusDto,
  UserUpdateDto,
  UserUpdateRolesDto,
  UserVo,
  UserWebDavToggleDto,
} from '@/types/auth'
import type { UserMigrationSubmitDto, UserMigrationTaskVo, UserStorageDto, UserSyncConfigUpdateDto, UserSyncConfigVo, UserSyncTaskVo } from '@/types/storage-space'

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

export function getCurrentUserProfile(): Promise<UserProfileVo> {
  return get<UserProfileVo>('/users/me')
}

export function updateCurrentUserProfile(dto: UserProfileUpdateDto): Promise<UserProfileVo> {
  return put<UserProfileVo>('/users/me', dto)
}

export function changePassword(dto: ChangePasswordDto): Promise<void> {
  return put<void>('/users/me/password', dto)
}

export function toggleWebDav(dto: UserWebDavToggleDto): Promise<UserProfileVo> {
  return put<UserProfileVo>('/users/me/webdav', dto)
}

export function bindUserStorageSpace(userId: string, dto: UserStorageDto): Promise<void> {
  return put<void>(`/users/${userId}/storage`, dto)
}

export function submitUserMigration(userId: string, dto: UserMigrationSubmitDto): Promise<UserMigrationTaskVo> {
  return post<UserMigrationTaskVo>(`/admin/users/${userId}/migrate`, dto)
}

export function getUserMigrationTask(userId: string): Promise<UserMigrationTaskVo | null> {
  return get<UserMigrationTaskVo | null>(`/admin/users/${userId}/migration-task`)
}

export function submitUserSync(userId: string): Promise<UserSyncTaskVo> {
  return post<UserSyncTaskVo>(`/admin/users/${userId}/sync/immediate`)
}

export function getUserSyncTask(userId: string): Promise<UserSyncTaskVo | null> {
  return get<UserSyncTaskVo | null>(`/admin/users/${userId}/sync/task`)
}

export function getUserSyncConfig(userId: string): Promise<UserSyncConfigVo> {
  return get<UserSyncConfigVo>(`/admin/users/${userId}/sync/config`)
}

export function updateUserSyncConfig(userId: string, dto: UserSyncConfigUpdateDto): Promise<UserSyncConfigVo> {
  return put<UserSyncConfigVo>(`/admin/users/${userId}/sync/config`, dto)
}
