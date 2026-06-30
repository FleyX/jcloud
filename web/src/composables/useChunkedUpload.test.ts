import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTransferStore } from '@/store/transfer'
import { useChunkedUpload } from './useChunkedUpload'
import type { ConflictItemVo, FileNodeVo } from '@/types/file'

vi.mock('@/api/file', () => ({
  initChunkedUpload: vi.fn(),
  uploadChunk: vi.fn(),
  listUploadedChunks: vi.fn(),
  completeChunkedUpload: vi.fn(),
  preCheckUpload: vi.fn(),
  tryInstantUpload: vi.fn(),
}))

vi.mock('@/utils/fileHash', () => ({
  identityHash: vi.fn(),
  fullHash: vi.fn(),
}))

const {
  initChunkedUpload,
  uploadChunk,
  listUploadedChunks,
  completeChunkedUpload,
  preCheckUpload,
  tryInstantUpload,
} = await import('@/api/file')

const { identityHash } = await import('@/utils/fileHash')

function createFile(name: string, content: string): File {
  return new File([new TextEncoder().encode(content)], name)
}

function createPreCheckResponse(items: { clientFileId: string; conflicts?: ConflictItemVo[]; candidates?: FileNodeVo[] }[]) {
  return items.map((item) => ({
    clientFileId: item.clientFileId,
    status: 'success' as const,
    data: {
      conflicts: item.conflicts ?? [],
      candidates: item.candidates ?? [],
    },
  }))
}

function createInitResponse(items: { clientFileId: string }[]) {
  return items.map((item) => ({
    clientFileId: item.clientFileId,
    status: 'success' as const,
    data: { uploadId: 'u-' + item.clientFileId, chunkSize: 1024, totalChunks: 1 },
  }))
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  vi.mocked(identityHash).mockResolvedValue('mock-partial-hash')
  vi.mocked(preCheckUpload).mockImplementation(async (req) =>
    createPreCheckResponse(req.items.map((item: { clientFileId: string }) => ({ clientFileId: item.clientFileId }))),
  )
  vi.mocked(tryInstantUpload).mockResolvedValue(null)
  vi.mocked(initChunkedUpload).mockImplementation(async (req) =>
    createInitResponse(req.items.map((item: { clientFileId: string }) => ({ clientFileId: item.clientFileId }))),
  )
  vi.mocked(uploadChunk).mockReset()
  vi.mocked(listUploadedChunks).mockReset()
  vi.mocked(completeChunkedUpload).mockReset()
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

    vi.mocked(listUploadedChunks).mockResolvedValue([])
    vi.mocked(uploadChunk).mockResolvedValue({ chunkIndex: 0, status: 'success' })
    vi.mocked(completeChunkedUpload).mockResolvedValue(node)

    const store = useTransferStore()
    const { upload } = useChunkedUpload()
    const onComplete = vi.fn()

    const result = await upload(file, '0', undefined, onComplete)

    expect(result).toEqual(node)
    expect(store.uploadQueue).toHaveLength(1)
    expect(store.uploadQueue[0].status).toBe('success')
    expect(store.uploadQueue[0].progress).toBe(100)
    expect(onComplete).toHaveBeenCalledOnce()
    expect(uploadChunk).toHaveBeenCalledOnce()
    expect(preCheckUpload).toHaveBeenCalledWith(expect.objectContaining({
      items: [expect.objectContaining({
        fileName: file.name,
        size: file.size,
        parentId: '0',
        relativePath: undefined,
        partialHash: 'mock-partial-hash',
      })],
    }))
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

    vi.mocked(listUploadedChunks).mockResolvedValue([0])
    vi.mocked(uploadChunk).mockResolvedValue({ chunkIndex: 0, status: 'success' })
    vi.mocked(completeChunkedUpload).mockResolvedValue(node)

    const { upload } = useChunkedUpload()
    const result = await upload(file)

    expect(result).toEqual(node)
    expect(uploadChunk).not.toHaveBeenCalled()
  })

  it('should instant upload when candidate exists', async () => {
    const file = createFile('hello.txt', 'hello world')
    const candidate: FileNodeVo = {
      id: 'c1',
      userId: '1',
      parentId: '0',
      name: 'hello.txt',
      type: 'file',
      size: String(file.size),
      storageSpaceId: '1',
      pathName: '/',
      hash: 'mock-hash',
      status: 1,
    }
    const instantNode: FileNodeVo = {
      ...candidate,
      id: 'n1',
    }

    vi.mocked(preCheckUpload).mockImplementation(async (req) =>
      createPreCheckResponse(req.items.map((item: { clientFileId: string }) => ({
        clientFileId: item.clientFileId,
        candidates: [candidate],
      }))),
    )
    vi.mocked(tryInstantUpload).mockResolvedValue(instantNode)

    const store = useTransferStore()
    const { upload } = useChunkedUpload()
    const onComplete = vi.fn()

    const result = await upload(file, '0', undefined, onComplete)

    expect(result).toEqual(instantNode)
    expect(store.uploadQueue[0].status).toBe('success')
    expect(onComplete).toHaveBeenCalledOnce()
    expect(tryInstantUpload).toHaveBeenCalledWith(file, candidate, '0', undefined, undefined)
    expect(initChunkedUpload).not.toHaveBeenCalled()
  })

  it('should resolve conflict via callback and skip upload', async () => {
    const file = createFile('hello.txt', 'hello world')
    const conflict = {
      sourceId: '',
      sourceName: 'hello.txt',
      sourceType: 'file' as const,
      existingId: 'e1',
      existingName: 'hello.txt',
      existingType: 'file' as const,
    }

    vi.mocked(preCheckUpload).mockImplementation(async (req) =>
      createPreCheckResponse(req.items.map((item: { clientFileId: string }) => ({
        clientFileId: item.clientFileId,
        conflicts: [conflict],
      }))),
    )

    const store = useTransferStore()
    const { upload } = useChunkedUpload()
    const resolveConflict = vi.fn().mockResolvedValue('skip')

    const result = await upload(file, '0', undefined, undefined, resolveConflict)

    expect(result).toBeUndefined()
    expect(store.uploadQueue).toHaveLength(0)
    expect(resolveConflict).toHaveBeenCalledWith(conflict)
    expect(initChunkedUpload).not.toHaveBeenCalled()
  })
})
