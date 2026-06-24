import { del, get, patch, post, put } from './request'
import type {
  PermissionSaveDto,
  PermissionStatusDto,
  PermissionTreeVo,
  PermissionUpdateDto,
  PermissionVo,
  ResourceVo,
} from '@/types/auth'

export function fetchPermissionTree(): Promise<PermissionTreeVo[]> {
  return get<PermissionTreeVo[]>('/permissions/tree')
}

export function fetchPermission(permissionId: string): Promise<PermissionVo> {
  return get<PermissionVo>(`/permissions/${permissionId}`)
}

export function createPermission(dto: PermissionSaveDto): Promise<PermissionVo> {
  return post<PermissionVo>('/permissions', dto)
}

export function updatePermission(permissionId: string, dto: PermissionUpdateDto): Promise<PermissionVo> {
  return put<PermissionVo>(`/permissions/${permissionId}`, dto)
}

export function deletePermission(permissionId: string): Promise<void> {
  return del<void>(`/permissions/${permissionId}`)
}

export function updatePermissionStatus(permissionId: string, dto: PermissionStatusDto): Promise<void> {
  return patch<void>(`/permissions/${permissionId}/status`, dto)
}

export function fetchResources(): Promise<ResourceVo[]> {
  return get<ResourceVo[]>('/permissions/resources')
}
