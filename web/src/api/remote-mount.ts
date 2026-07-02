import { del, get, post, put } from './request'
import type {
  RemoteMountDetailVo,
  RemoteMountHealthVo,
  RemoteMountPageQuery,
  RemoteMountPageResult,
  RemoteMountSaveDto,
  RemoteMountSyncConfigUpdateDto,
  RemoteMountUpdateDto,
  RemoteMountVo,
  RemoteSyncTaskPageQuery,
  RemoteSyncTaskPageResult,
  RemoteSyncTaskVo,
} from '@/types/remote-mount'

/**
 * 分页查询当前用户的远程挂载
 */
export function fetchRemoteMountPage(params: RemoteMountPageQuery): Promise<RemoteMountPageResult> {
  return get<RemoteMountPageResult>('/remote-mounts', params as Record<string, unknown>)
}

/**
 * 创建远程挂载
 */
export function createRemoteMount(dto: RemoteMountSaveDto): Promise<RemoteMountVo> {
  return post<RemoteMountVo>('/remote-mounts', dto)
}

/**
 * 查看远程挂载详情
 */
export function fetchRemoteMountDetail(id: string): Promise<RemoteMountDetailVo> {
  return get<RemoteMountDetailVo>(`/remote-mounts/${id}`)
}

/**
 * 更新远程挂载
 */
export function updateRemoteMount(id: string, dto: RemoteMountUpdateDto): Promise<RemoteMountVo> {
  return put<RemoteMountVo>(`/remote-mounts/${id}`, dto)
}

/**
 * 删除远程挂载
 */
export function deleteRemoteMount(id: string): Promise<void> {
  return del<void>(`/remote-mounts/${id}`)
}

/**
 * 测试远程挂载连接
 */
export function testRemoteConnection(dto: RemoteMountSaveDto): Promise<void> {
  return post<void>('/remote-mounts/test-connection', dto)
}

/**
 * 立即同步远程挂载
 */
export function submitRemoteMountSync(id: string): Promise<RemoteSyncTaskVo> {
  return post<RemoteSyncTaskVo>(`/remote-mounts/${id}/sync`)
}

/**
 * 查询远程挂载最新同步任务
 */
export function getRemoteMountSyncTask(id: string): Promise<RemoteSyncTaskVo | null> {
  return get<RemoteSyncTaskVo | null>(`/remote-mounts/${id}/sync/task`)
}

/**
 * 更新远程挂载同步配置
 */
export function updateRemoteMountSyncConfig(
  id: string,
  dto: RemoteMountSyncConfigUpdateDto,
): Promise<void> {
  return put<void>(`/remote-mounts/${id}/sync-config`, dto)
}

/**
 * 分页查询远程同步任务历史
 */
export function fetchRemoteSyncTaskPage(params: RemoteSyncTaskPageQuery): Promise<RemoteSyncTaskPageResult> {
  return get<RemoteSyncTaskPageResult>('/remote-mounts/sync-tasks', params as Record<string, unknown>)
}

/**
 * 检查当前用户所有远程挂载的健康状态
 */
export function checkRemoteMountHealth(): Promise<RemoteMountHealthVo[]> {
  return post<RemoteMountHealthVo[]>('/remote-mounts/health-check')
}
