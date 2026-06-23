/**
 * 角色视图对象
 */
export interface RoleVo {
  id: string
  code: string
  name: string
  description?: string
}

/**
 * 权限资源类型
 */
export type PermissionType = 'MENU' | 'BUTTON'

/**
 * 权限资源视图对象
 */
export interface Permission {
  id: string
  code: string
  name: string
  type: PermissionType
  path?: string
}

/**
 * 用户视图对象
 */
export interface UserVo {
  id: string
  username: string
  nickname?: string
  email?: string
  status: number
  isAdmin: boolean
  roles: RoleVo[]
  createTime?: string
  updateTime?: string
}

/**
 * 登录成功返回对象
 */
export interface LoginVo {
  token: string
  userInfo: UserVo
  permissions: string[]
}

/**
 * 通用后端响应包装
 */
export interface ApiResponse<T> {
  code: number
  msg: string
  data: T
  traceId?: string
}

/**
 * 用户分页查询参数
 */
export interface UserPageQuery {
  username?: string
  nickname?: string
  status?: number
  pageNum?: number
  pageSize?: number
}

/**
 * 用户状态变更 DTO
 */
export interface UserStatusDto {
  userId: string
  status: number
}

/**
 * 修改用户角色 DTO
 */
export interface UserUpdateRolesDto {
  userId: string
  roleIds: string[]
}

/**
 * 新增用户 DTO
 */
export interface UserSaveDto {
  username: string
  password: string
  email?: string
  nickname?: string
}

/**
 * 编辑用户 DTO
 */
export interface UserUpdateDto {
  id: string
  nickname?: string
  email?: string
  roleIds?: string[]
  status?: number
  password?: string
}

/**
 * 批量修改用户状态 DTO
 */
export interface BatchUserStatusDto {
  userIds: string[]
  status: number
}

/**
 * 分页响应
 */
export interface PageResult<T> {
  records: T[]
  total: string
  size: string
  current: string
  pages: string
}
