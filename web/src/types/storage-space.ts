/**
 * 存储空间类型枚举
 */
export type StorageSpaceType = 'USER' | 'SYSTEM'

/**
 * 存储空间视图对象
 */
export interface StorageSpaceVo {
  id: string
  name: string
  path: string
  type: StorageSpaceType
  capacity: string
  usedSpace: string
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
  type?: StorageSpaceType
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
  type: StorageSpaceType
  capacity: string
  remark?: string
}

/**
 * 存储空间更新 DTO
 */
export interface StorageSpaceUpdateDto {
  id: string
  name: string
  path: string
  type: StorageSpaceType
  capacity: string
  status: number
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
