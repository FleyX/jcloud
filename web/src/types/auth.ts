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
  webdavEnabled?: boolean
  roles: RoleVo[]
  storageSpaceId?: string
  storageSpaceName?: string
  quota?: string
  quotaUnit?: string
  usedSpace?: string
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
  webdavEnabled?: boolean
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
 * 用户 WebDAV 访问开关 DTO
 */
export interface UserWebDavToggleDto {
  enabled: boolean
}

/**
 * 登录成功返回对象
 */
export interface LoginVo {
  token: string
  refreshToken: string
  deviceId: string
  userInfo: UserVo
  resources: string[]
  initialized: boolean
}

/**
 * 刷新令牌成功后的新令牌对
 */
export interface TokenPairVo {
  token: string
  refreshToken: string
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
  storageSpaceId: string
  quota: string
  quotaUnit: string
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
  quota?: string
  quotaUnit?: string
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
  permissionCodes?: string[]
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
  permissionCodes?: string[]
}

/**
 * 角色更新 DTO
 */
export interface RoleUpdateDto {
  name: string
  description?: string
  status?: number
  permissionCodes?: string[]
}

/**
 * 角色状态 DTO
 */
export interface RoleStatusDto {
  status: number
}

/**
 * 资源视图对象（只读，来自后端内存权限注册表）
 */
export interface ResourceVo {
  code: string
  name: string
}

/**
 * 权限树视图对象（只读，来自后端内存权限注册表）
 */
export interface PermissionTreeVo {
  code: string
  name: string
  parentCode?: string
  resources?: ResourceVo[]
  children?: PermissionTreeVo[]
  level?: number // 仅前端渲染使用
}
