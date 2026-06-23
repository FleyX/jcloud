import { get } from './request'
import type { RoleVo } from '@/types/auth'

export function fetchAllRoles(): Promise<RoleVo[]> {
  return get<RoleVo[]>('/roles')
}
