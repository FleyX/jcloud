import { get, post, put, del } from './request'
import type { PageResult } from '@/types/auth'
import type {
  PublicShareItemQuery,
  PublicShareVo,
  ShareAccessRequest,
  ShareCreateRequest,
  ShareDetailVo,
  SharePageQuery,
  ShareUpdateRequest,
  ShareVo,
} from '@/types/share'
import type { FileBatchDownloadRequest, FileNodeVo, FileZipTaskVo } from '@/types/file'

/**
 * 创建分享
 */
export function createShare(body: ShareCreateRequest): Promise<ShareVo> {
  return post<ShareVo>('/shares', body)
}

/**
 * 分页查询我的分享
 */
export function fetchSharePage(params: SharePageQuery): Promise<PageResult<ShareVo>> {
  return get<PageResult<ShareVo>>('/shares', params as Record<string, unknown>)
}

/**
 * 查看分享详情
 */
export function fetchShareDetail(id: string): Promise<ShareDetailVo> {
  return get<ShareDetailVo>(`/shares/${id}`)
}

/**
 * 更新分享
 */
export function updateShare(id: string, body: ShareUpdateRequest): Promise<ShareVo> {
  return put<ShareVo>(`/shares/${id}`, body)
}

/**
 * 删除分享
 */
export function deleteShare(id: string): Promise<void> {
  return del<void>(`/shares/${id}`)
}

/**
 * 获取公开分享信息
 */
export function fetchPublicShare(code: string): Promise<PublicShareVo> {
  return get<PublicShareVo>(`/s/${code}`)
}

/**
 * 校验分享访问密码
 */
export function accessShareWithPassword(code: string, body: ShareAccessRequest): Promise<string> {
  return post<string>(`/s/${code}/access`, body)
}

/**
 * 获取公开分享项列表
 */
export function fetchPublicShareItems(
  code: string,
  params: PublicShareItemQuery,
): Promise<FileNodeVo[]> {
  return get<FileNodeVo[]>(`/s/${code}/items`, params as Record<string, unknown>)
}

/**
 * 公开下载单个文件 URL
 */
export function downloadPublicFileUrl(code: string, fileId: string, token?: string): string {
  const query = token ? `?token=${encodeURIComponent(token)}` : ''
  return `/jcloud/api/s/${code}/files/${fileId}/download${query}`
}

/**
 * 公开预览文件 URL
 */
export function previewPublicFileUrl(
  code: string,
  fileId: string,
  type: 'thumbnail' | 'poster' | 'text',
  token?: string,
): string {
  const query = new URLSearchParams({ type })
  if (token) {
    query.append('token', token)
  }
  return `/jcloud/api/s/${code}/files/${fileId}/preview?${query.toString()}`
}

/**
 * 公开批量下载
 */
export function downloadPublicBatch(
  code: string,
  body: FileBatchDownloadRequest,
  token?: string,
): Promise<FileZipTaskVo> {
  const query = token ? { token } : undefined
  return post<FileZipTaskVo>(`/s/${code}/batch-download`, body, query as Record<string, unknown>)
}

/**
 * 查询公开批量下载任务状态
 */
export function fetchPublicBatchTaskStatus(
  code: string,
  taskId: string,
  token?: string,
): Promise<FileZipTaskVo> {
  const query = token ? { token } : undefined
  return get<FileZipTaskVo>(`/s/${code}/batch-download/${taskId}/status`, query as Record<string, unknown>)
}

/**
 * 下载公开批量下载结果 URL
 */
export function downloadPublicBatchResultUrl(code: string, taskId: string, token?: string): string {
  const query = token ? `?token=${encodeURIComponent(token)}` : ''
  return `/jcloud/api/s/${code}/batch-download/${taskId}${query}`
}
