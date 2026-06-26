import { useNotificationStore } from '@/store/notification'
import { useUserStore } from '@/store/user'
import { fullHash, identityHash } from '@/utils/fileHash'
import { get, post } from './request'
import type { PageResult } from '@/types/auth'
import type { DownloadProgress } from '@/store/transfer'
import type {
  ConflictItemVo,
  FileBatchDownloadRequest,
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
  FileZipTaskVo,
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

/**
 * 分片上传初始化响应。
 */
export interface ChunkedUploadInitResponse {
  uploadId: string
  chunkSize: number
  totalChunks: number
}

/**
 * 分片上传响应。
 */
export interface ChunkedUploadChunkResponse {
  chunkIndex: number
  status: string
}

/**
 * 初始化分片上传任务。
 */
export function initChunkedUpload(fileName: string, size: number, parentId = '0'): Promise<ChunkedUploadInitResponse> {
  return post<ChunkedUploadInitResponse>('/files/chunked-upload/init', { fileName, size, parentId })
}

/**
 * 上传单个分片，支持进度与取消。
 */
export function uploadChunk(
  uploadId: string,
  index: number,
  chunkHash: string,
  chunk: Blob,
  onProgress?: (loaded: number) => void,
  signal?: AbortSignal,
): Promise<ChunkedUploadChunkResponse> {
  const userStore = useUserStore()
  const formData = new FormData()
  formData.append('index', String(index))
  formData.append('chunkHash', chunkHash)
  formData.append('chunk', chunk, 'chunk')

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `/jcloud/api/files/chunked-upload/${uploadId}/chunks`)
    xhr.setRequestHeader('Authorization', `Bearer ${userStore.token}`)

    if (onProgress) {
      xhr.upload.addEventListener('progress', (event) => {
        if (event.lengthComputable) {
          onProgress(event.loaded)
        }
      })
    }

    xhr.addEventListener('load', () => {
      if (xhr.status < 200 || xhr.status >= 300) {
        reject(new Error('上传分片失败'))
        return
      }
      try {
        const json = JSON.parse(xhr.responseText) as { code: number; msg: string; data: ChunkedUploadChunkResponse }
        if (json.code !== 200) {
          reject(new Error(json.msg || '上传分片失败'))
          return
        }
        resolve(json.data)
      } catch {
        reject(new Error('解析分片响应失败'))
      }
    })

    xhr.addEventListener('error', () => reject(new Error('上传分片失败')))
    xhr.addEventListener('abort', () => reject(new Error('上传已取消')))

    if (signal) {
      signal.addEventListener('abort', () => xhr.abort())
    }

    xhr.send(formData)
  })
}

/**
 * 查询已上传的分片索引列表。
 */
export function listUploadedChunks(uploadId: string): Promise<number[]> {
  return get<number[]>(`/files/chunked-upload/${uploadId}/chunks`)
}

/**
 * 完成分片上传并创建文件节点。
 */
export function completeChunkedUpload(uploadId: string): Promise<FileNodeVo> {
  return post<FileNodeVo>(`/files/chunked-upload/${uploadId}/complete`)
}

export function fetchTextPreview(id: string): Promise<PreviewTextResponse> {
  return get<PreviewTextResponse>(`/files/${id}/preview`, { type: 'text' })
}

/**
 * 批量下载文件/文件夹。
 * 小文件直接流式下载；大文件后台预生成后自动轮询并下载。
 */
export async function downloadBatchFiles(
  ids: string[],
  fileName = 'archive.zip',
  onProgress?: (progress: DownloadProgress) => void,
): Promise<void> {
  const userStore = useUserStore()
  const response = await fetch('/jcloud/api/files/batch-download', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${userStore.token}`,
    },
    body: JSON.stringify({ ids } satisfies FileBatchDownloadRequest),
  })

  if (!response.ok) {
    const json = await response.json().catch(() => ({}))
    throw new Error(json.msg || '下载失败')
  }

  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    const json = (await response.json()) as { code: number; msg: string; data: FileZipTaskVo }
    if (json.code !== 200) {
      throw new Error(json.msg || '下载失败')
    }
    await pollAndDownloadTask(json.data.taskId, fileName, onProgress)
    return
  }

  await saveResponseToFile(response, fileName, onProgress)
}

async function pollAndDownloadTask(
  taskId: string,
  fileName: string,
  onProgress?: (progress: DownloadProgress) => void,
): Promise<void> {
  const userStore = useUserStore()
  const maxAttempts = 120
  const intervalMs = 1000

  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    await sleep(intervalMs)
    const response = await fetch(`/jcloud/api/files/batch-download/${taskId}/status`, {
      headers: { Authorization: `Bearer ${userStore.token}` },
    })
    const json = (await response.json()) as { code: number; msg: string; data: FileZipTaskVo }
    if (json.code !== 200) {
      throw new Error(json.msg || '查询下载任务失败')
    }
    const task = json.data
    if (task.status === 'failed') {
      throw new Error(task.message || '下载任务失败')
    }
    if (task.status === 'completed') {
      const downloadResponse = await fetch(`/jcloud/api/files/batch-download/${taskId}`, {
        headers: { Authorization: `Bearer ${userStore.token}` },
      })
      if (!downloadResponse.ok) {
        throw new Error('下载 ZIP 失败')
      }
      await saveResponseToFile(downloadResponse, fileName, onProgress, task.totalBytes ? Number(task.totalBytes) : undefined)
      return
    }
    if (onProgress) {
      // 运行中先展示 50% 进度，完成后再跳到 100%
      onProgress({ progress: attempt % 2 === 0 ? 45 : 50 })
    }
  }
  throw new Error('下载任务超时')
}

async function saveResponseToFile(
  response: Response,
  fileName: string,
  onProgress?: (progress: DownloadProgress) => void,
  totalBytes?: number,
): Promise<void> {
  const reader = response.body?.getReader()
  if (!reader) {
    throw new Error('无法读取下载响应')
  }

  const total = totalBytes || Number(response.headers.get('content-length')) || 0
  let loaded = 0
  const chunks: Uint8Array[] = []

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    chunks.push(value)
    loaded += value.length
    if (onProgress) {
      const progress = total > 0 ? Math.min(Math.round((loaded / total) * 100), 100) : 0
      onProgress({ progress, loadedBytes: loaded, totalBytes: total })
    }
  }

  const blob = new Blob(chunks as BlobPart[])
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(link.href)

  if (onProgress) {
    onProgress({ progress: 100, loadedBytes: loaded, totalBytes: total })
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
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
