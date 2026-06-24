import { del, get, patch, post, put } from './request'
import type { PageResult, RolePageQuery, RoleSaveDto, RoleStatusDto, RoleUpdateDto, RoleVo } from '@/types/auth'

export function fetchAllRoles(): Promise<RoleVo[]> {
  return get<RoleVo[]>('/roles')
}

export function fetchRolePage(params: RolePageQuery): Promise<PageResult<RoleVo>> {
  return get<PageResult<RoleVo>>('/roles/page', params as Record<string, unknown>)
}

export function fetchRole(roleId: string): Promise<RoleVo> {
  return get<RoleVo>(`/roles/${roleId}`)
}

export function createRole(dto: RoleSaveDto): Promise<RoleVo> {
  return post<RoleVo>('/roles', dto)
}

export function updateRole(roleId: string, dto: RoleUpdateDto): Promise<RoleVo> {
  return put<RoleVo>(`/roles/${roleId}`, dto)
}

export function deleteRole(roleId: string): Promise<void> {
  return del<void>(`/roles/${roleId}`)
}

export function updateRoleStatus(roleId: string, dto: RoleStatusDto): Promise<void> {
  return patch<void>(`/roles/${roleId}/status`, dto)
}
