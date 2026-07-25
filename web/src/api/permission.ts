import { get } from './request'
import type { PermissionTreeVo } from '@/types/auth'

/**
 * 查询权限树（只读，供角色分配界面使用）
 */
export function fetchPermissionTree(): Promise<PermissionTreeVo[]> {
  return get<PermissionTreeVo[]>('/permissions/tree')
}
