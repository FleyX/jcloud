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
 * 当前登录用户个人信息视图
 */
export interface UserProfileVo {
  id: string
  username: string
  nickname?: string
  email?: string
}

/**
 * 更新当前登录用户个人信息 DTO
 */
export interface UserProfileUpdateDto {
  email?: string
  nickname?: string
}

/**
 * 修改当前登录用户密码 DTO
 */
export interface ChangePasswordDto {
  currentPassword: string
  newPassword: string
  confirmPassword: string
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


/**
 * 角色视图对象
 */
export interface RoleVo {
  id: string
  code: string
  name: string
  description?: string
  status: number
  permissionIds?: string[]
  createTime?: string
  updateTime?: string
}

/**
 * 角色分页查询参数
 */
export interface RolePageQuery {
  code?: string
  name?: string
  status?: number
  pageNum?: number
  pageSize?: number
}

/**
 * 角色保存 DTO
 */
export interface RoleSaveDto {
  code: string
  name: string
  description?: string
  status?: number
  permissionIds?: string[]
}

/**
 * 角色更新 DTO
 */
export interface RoleUpdateDto {
  name: string
  description?: string
  status?: number
  permissionIds?: string[]
}

/**
 * 角色状态 DTO
 */
export interface RoleStatusDto {
  status: number
}

/**
 * 权限树视图对象
 */
export interface PermissionTreeVo {
  id: string
  code: string
  name: string
  parentId?: string
  status: number
  children?: PermissionTreeVo[]
  level?: number // 仅前端渲染使用
}

/**
 * 权限详情视图对象
 */
export interface PermissionVo {
  id: string
  code: string
  name: string
  parentId?: string
  status: number
  resourceIds?: string[]
}

/**
 * 权限保存 DTO
 */
export interface PermissionSaveDto {
  code: string
  name: string
  parentId?: string
  status: number
  resourceIds?: string[]
}

/**
 * 权限更新 DTO
 */
export interface PermissionUpdateDto {
  name: string
  parentId?: string
  status: number
  resourceIds?: string[]
}

/**
 * 权限状态 DTO
 */
export interface PermissionStatusDto {
  status: number
}

/**
 * 资源视图对象
 */
export interface ResourceVo {
  id: string
  code: string
  name: string
  type: 'PUBLIC' | 'PAGE' | 'LOGIN' | 'API'
  status: number
}
