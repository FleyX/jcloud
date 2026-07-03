import type { PageResult } from './auth'

/**
 * 远程挂载协议类型
 */
export type RemoteMountType = 'webdav' | 's3' | 'nfs'

/**
 * 远程挂载视图对象
 */
export interface RemoteMountVo {
  id: string
  name: string
  type: RemoteMountType
  enabled: number
  cronExpr?: string
  nextSyncTime?: string
  lastSyncTime?: string
  lastSyncStatus?: string
  lastSyncError?: string
  createTime?: string
  updateTime?: string
}

/**
 * 远程挂载详情视图对象
 */
export interface RemoteMountDetailVo extends RemoteMountVo {
  url: string
  username?: string
  password?: string
}

/**
 * 创建远程挂载请求
 */
export interface RemoteMountSaveDto {
  name: string
  type: RemoteMountType
  url: string
  username?: string
  password?: string
  cronExpr?: string
  enabled: number
}

/**
 * 更新远程挂载请求
 */
export interface RemoteMountUpdateDto {
  id: string
  name: string
  type: RemoteMountType
  url: string
  username?: string
  password?: string
  cronExpr?: string
  enabled: number
}

/**
 * 远程挂载同步配置更新请求
 */
export interface RemoteMountSyncConfigUpdateDto {
  remoteMountId: string
  cronExpr: string
  enabled: number
}

/**
 * 远程同步任务视图对象
 */
export interface RemoteSyncTaskVo {
  id: string
  remoteMountId: string
  type?: string
  status: string
  startTime?: string
  endTime?: string
  totalCount?: string
  successCount?: string
  failCount?: string
  errorMsg?: string
  createTime?: string
  updateTime?: string
}

/**
 * 远程挂载健康状态视图对象
 */
export interface RemoteMountHealthVo {
  id: string
  name: string
  status: 'ok' | 'error'
  message?: string
}

/**
 * 远程挂载分页查询参数
 */
export interface RemoteMountPageQuery {
  name?: string
  pageNum?: number
  pageSize?: number
}

/**
 * 远程同步任务分页查询参数
 */
export interface RemoteSyncTaskPageQuery {
  remoteMountId?: string
  status?: string
  pageNum?: number
  pageSize?: number
}

/**
 * 远程挂载分页响应
 */
export type RemoteMountPageResult = PageResult<RemoteMountVo>

/**
 * 远程同步任务分页响应
 */
export type RemoteSyncTaskPageResult = PageResult<RemoteSyncTaskVo>
