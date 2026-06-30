/**
 * 存储空间视图对象
 */
export interface StorageSpaceVo {
  id: string
  name: string
  path: string
  capacity: string
  usedSpace: string
  freeSpace: string
  isPrimary: number
  status: number
  remark?: string
  createTime?: string
  updateTime?: string
}

/**
 * 存储空间分页查询参数
 */
export interface StorageSpacePageQuery {
  name?: string
  status?: number
  pageNum?: number
  pageSize?: number
}

/**
 * 存储空间保存 DTO
 */
export interface StorageSpaceSaveDto {
  name: string
  path: string
  remark?: string
}

/**
 * 存储空间更新 DTO
 */
export interface StorageSpaceUpdateDto {
  id: string
  name: string
  path: string
  status: number
  isPrimary: number
  remark?: string
}

/**
 * 用户存储空间绑定 DTO
 */
export interface UserStorageDto {
  userId: string
  storageSpaceId: string
  quota: string
}

/**
 * 用户存储空间迁移任务视图
 */
export interface UserMigrationTaskVo {
  id: string
  userId: string
  sourceSpaceId: string
  targetSpaceId: string
  newQuota: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  totalBytes: string
  migratedBytes: string
  errorMsg?: string
  createTime?: string
  updateTime?: string
}

/**
 * 用户存储空间迁移提交 DTO
 */
export interface UserMigrationSubmitDto {
  userId: string
  targetSpaceId: string
  newQuota?: string
}

/**
 * 系统数据目录配置视图
 */
export interface SystemStorageConfigVo {
  systemSpaceId?: string
}

/**
 * 系统数据目录配置更新 DTO
 */
export interface SystemStorageConfigUpdateDto {
  systemSpaceId: string
}

/**
 * 系统初始化状态视图
 */
export interface SystemInitStatusVo {
  initialized: boolean
  admin: boolean
}

/**
 * 用户存储空间同步任务视图
 */
export interface UserSyncTaskVo {
  id: string
  userId: string
  type: 'manual' | 'scheduled'
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'PARTIAL'
  startTime?: string
  endTime?: string
  totalCount?: number
  successCount?: number
  failCount?: number
  errorMsg?: string
  createTime?: string
  updateTime?: string
}

/**
 * 用户存储空间同步配置视图
 */
export interface UserSyncConfigVo {
  userId: string
  cronExpr: string
  enabled: number
  nextSyncTime?: string
  createTime?: string
  updateTime?: string
}

/**
 * 用户存储空间同步配置更新 DTO
 */
export interface UserSyncConfigUpdateDto {
  userId: string
  cronExpr: string
  enabled: number
}

/**
 * 系统初始化单空间项
 */
export interface InitSpaceItem {
  name: string
  path: string
  remark?: string
}

/**
 * 系统初始化 DTO
 */
export interface SystemInitDto {
  spaces: InitSpaceItem[]
  primaryIndex: number
  systemDataIndex: number
}
