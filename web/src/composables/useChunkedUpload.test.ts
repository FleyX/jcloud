import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTransferStore } from '@/store/transfer'
import { useChunkedUpload } from './useChunkedUpload'
import type { FileNodeVo } from '@/types/file'

vi.mock('@/api/file', () => ({
  initChunkedUpload: vi.fn(),
  uploadChunk: vi.fn(),
  listUploadedChunks: vi.fn(),
  completeChunkedUpload: vi.fn(),
}))

const { initChunkedUpload, uploadChunk, listUploadedChunks, completeChunkedUpload } = await import('@/api/file')

function createFile(name: string, content: string): File {
  return new File([new TextEncoder().encode(content)], name)
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

describe('useChunkedUpload', () => {
  it('should upload a single chunk file and complete task', async () => {
    const file = createFile('hello.txt', 'hello world')
    const node: FileNodeVo = {
      id: 'n1',
      userId: '1',
      parentId: '0',
      name: 'hello.txt',
      type: 'file',
      size: String(file.size),
      storageSpaceId: '1',
      pathName: '/',
      status: 1,
    }

    vi.mocked(initChunkedUpload).mockResolvedValue({
      uploadId: 'u1',
      chunkSize: 1024,
      totalChunks: 1,
    })
    vi.mocked(listUploadedChunks).mockResolvedValue([])
    vi.mocked(uploadChunk).mockResolvedValue({ chunkIndex: 0, status: 'success' })
    vi.mocked(completeChunkedUpload).mockResolvedValue(node)

    const store = useTransferStore()
    const { upload } = useChunkedUpload()
    const onComplete = vi.fn()

    const result = await upload(file, '0', onComplete)

    expect(result).toEqual(node)
    expect(store.uploadQueue).toHaveLength(1)
    expect(store.uploadQueue[0].status).toBe('success')
    expect(store.uploadQueue[0].progress).toBe(100)
    expect(onComplete).toHaveBeenCalledOnce()
    expect(uploadChunk).toHaveBeenCalledOnce()
  })

  it('should skip already uploaded chunks on resume', async () => {
    const file = createFile('hello.txt', 'hello world')
    const node: FileNodeVo = {
      id: 'n1',
      userId: '1',
      parentId: '0',
      name: 'hello.txt',
      type: 'file',
      size: String(file.size),
      storageSpaceId: '1',
      pathName: '/',
      status: 1,
    }

    vi.mocked(initChunkedUpload).mockResolvedValue({
      uploadId: 'u1',
      chunkSize: 1024,
      totalChunks: 1,
    })
    vi.mocked(listUploadedChunks).mockResolvedValue([0])
    vi.mocked(uploadChunk).mockResolvedValue({ chunkIndex: 0, status: 'success' })
    vi.mocked(completeChunkedUpload).mockResolvedValue(node)

    const { upload } = useChunkedUpload()
    const result = await upload(file)

    expect(result).toEqual(node)
    expect(uploadChunk).not.toHaveBeenCalled()
  })
})
