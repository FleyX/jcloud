import { del, get, post, put } from './request'
import type {
  PageResult,
} from '@/types/auth'
import type {
  StorageSpaceExpandDto,
  StorageSpacePageQuery,
  StorageSpaceSaveDto,
  StorageSpaceUpdateDto,
  StorageSpaceVo,
} from '@/types/storage-space'

export function fetchStorageSpacePage(params: StorageSpacePageQuery): Promise<PageResult<StorageSpaceVo>> {
  return get<PageResult<StorageSpaceVo>>('/admin/storage-spaces', params as Record<string, unknown>)
}

export function fetchStorageSpaceById(id: string): Promise<StorageSpaceVo> {
  return get<StorageSpaceVo>(`/admin/storage-spaces/${id}`)
}

export function createStorageSpace(dto: StorageSpaceSaveDto): Promise<StorageSpaceVo> {
  return post<StorageSpaceVo>('/admin/storage-spaces', dto)
}

export function updateStorageSpace(id: string, dto: StorageSpaceUpdateDto): Promise<StorageSpaceVo> {
  return put<StorageSpaceVo>(`/admin/storage-spaces/${id}`, dto)
}

export function deleteStorageSpace(id: string): Promise<void> {
  return del<void>(`/admin/storage-spaces/${id}`)
}

export function expandStorageSpace(id: string, dto: StorageSpaceExpandDto): Promise<StorageSpaceVo> {
  return post<StorageSpaceVo>(`/admin/storage-spaces/${id}/expand`, dto)
}
