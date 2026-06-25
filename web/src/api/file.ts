import { useNotificationStore } from '@/store/notification'
import { useUserStore } from '@/store/user'
import { fullHash, identityHash } from '@/utils/fileHash'
import { get, post } from './request'
import type { PageResult } from '@/types/auth'
import type {
  ConflictItemVo,
  FileCreateFolderRequest,
  FileDeleteRequest,
  FileExecuteOperationRequest,
  FileExecuteRestoreRequest,
  FileNodeVo,
  FilePageQuery,
  FilePermanentDeleteRequest,
  FilePreCheckOperationRequest,
  FilePreCheckRestoreRequest,
  FileRenameRequest,
  OperationResultVo,
  RecycleRecordVo,
} from '@/types/file'

export function fetchFilePage(params: FilePageQuery): Promise<PageResult<FileNodeVo>> {
  return get<PageResult<FileNodeVo>>('/files', params as Record<string, unknown>)
}

export async function uploadFile(file: File, parentId = '0'): Promise<FileNodeVo> {
  const instant = await tryInstantUpload(file, parentId)
  if (instant) {
    return instant
  }

  const userStore = useUserStore()
  const formData = new FormData()
  formData.append('file', file)

  const response = await fetch('/jcloud/api/files/upload', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${userStore.token}`,
    },
    body: formData,
  })
  return handleJsonResponse<FileNodeVo>(response)
}

/**
 * 尝试秒传。仅当文件小于 150MB 时启用；大文件直接返回 null 退化到普通上传。
 */
async function tryInstantUpload(file: File, parentId: string): Promise<FileNodeVo | null> {
  const partialHash = await identityHash(file)
  if (!partialHash) {
    return null
  }

  const candidates = await post<FileNodeVo[]>('/files/pre-check', {
    fileName: file.name,
    size: file.size,
    partialHash,
  })

  if (candidates.length === 0) {
    return null
  }

  const candidate = candidates[0]
  const hash = await fullHash(file)
  if (hash !== candidate.hash) {
    return null
  }

  const result = await post<FileNodeVo>('/files/instant', {
    candidateId: candidate.id,
    fullHash: hash,
    fileName: file.name,
    parentId,
  })

  useNotificationStore().success('秒传成功')
  return result
}

export function downloadFile(id: string): void {
  const userStore = useUserStore()
  fetch(`/jcloud/api/files/${id}/download`, {
    headers: {
      Authorization: `Bearer ${userStore.token}`,
    },
  })
    .then(async (response) => {
      if (!response.ok) {
        const json = await response.json().catch(() => ({}))
        throw new Error(json.msg || '下载失败')
      }
      const blob = await response.blob()
      const disposition = response.headers.get('content-disposition')
      let fileName = 'download'
      if (disposition) {
        const match = disposition.match(/filename="?([^"]+)"?/)
        if (match) {
          fileName = match[1]
        }
      }
      const link = document.createElement('a')
      link.href = URL.createObjectURL(blob)
      link.download = fileName
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      URL.revokeObjectURL(link.href)
    })
    .catch((err: Error) => {
      useNotificationStore().error(err.message)
    })
}

export function renameFile(dto: FileRenameRequest): Promise<FileNodeVo> {
  return post<FileNodeVo>('/files/rename', dto)
}

export function createFolder(dto: FileCreateFolderRequest): Promise<FileNodeVo> {
  return post<FileNodeVo>('/files/folders', dto)
}

export function preCheckOperation(dto: FilePreCheckOperationRequest): Promise<ConflictItemVo[]> {
  return post<ConflictItemVo[]>('/files/operations/pre-check', dto)
}

export function moveFiles(dto: FileExecuteOperationRequest): Promise<OperationResultVo[]> {
  return post<OperationResultVo[]>('/files/move', dto)
}

export function copyFiles(dto: FileExecuteOperationRequest): Promise<OperationResultVo[]> {
  return post<OperationResultVo[]>('/files/copy', dto)
}

export function deleteToTrash(dto: FileDeleteRequest): Promise<OperationResultVo[]> {
  return post<OperationResultVo[]>('/files/delete', dto)
}

export function fetchTrashPage(pageNum = 1, pageSize = 20): Promise<PageResult<RecycleRecordVo>> {
  return get<PageResult<RecycleRecordVo>>('/files/trash', { pageNum, pageSize })
}

export function preCheckRestore(dto: FilePreCheckRestoreRequest): Promise<ConflictItemVo[]> {
  return post<ConflictItemVo[]>('/files/trash/restore/pre-check', dto)
}

export function restoreFiles(dto: FileExecuteRestoreRequest): Promise<OperationResultVo[]> {
  return post<OperationResultVo[]>('/files/trash/restore', dto)
}

export function permanentDeleteTrash(dto: FilePermanentDeleteRequest): Promise<OperationResultVo[]> {
  return post<OperationResultVo[]>('/files/trash/permanent-delete', dto)
}

export interface PreviewTextResponse {
  content: string
}

export function previewFileUrl(id: string, type: 'thumbnail' | 'poster' | 'text'): string {
  return `/jcloud/api/files/${id}/preview?type=${type}`
}

export function fetchTextPreview(id: string): Promise<PreviewTextResponse> {
  return get<PreviewTextResponse>(`/files/${id}/preview`, { type: 'text' })
}

async function handleJsonResponse<T>(response: Response): Promise<T> {
  const json = (await response.json()) as { code: number; msg: string; data: T }
  if (json.code !== 200) {
    const message = json.msg || '请求失败'
    useNotificationStore().error(message)
    throw new Error(message)
  }
  return json.data
}
