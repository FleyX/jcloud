import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useBatchUpload } from './useBatchUpload'
import type { ConflictStrategy, FileNodeVo } from '@/types/file'

vi.mock('@/api/file', () => ({
  preCheckUpload: vi.fn(),
  tryInstantUpload: vi.fn(),
  initChunkedUpload: vi.fn(),
  uploadChunk: vi.fn(),
  listUploadedChunks: vi.fn(),
  completeChunkedUpload: vi.fn(),
}))

vi.mock('@/utils/fileHash', () => ({
  identityHash: vi.fn(),
}))

const {
  preCheckUpload,
  initChunkedUpload,
  listUploadedChunks,
  uploadChunk,
  completeChunkedUpload,
} = await import('@/api/file')

const { identityHash } = await import('@/utils/fileHash')

function createFile(name: string, content: string, relativePath = ''): File {
  const file = new File([new TextEncoder().encode(content)], name)
  if (relativePath) {
    Object.defineProperty(file, 'webkitRelativePath', {
      value: relativePath,
      writable: false,
    })
  }
  return file
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  vi.mocked(identityHash).mockResolvedValue('mock-partial-hash')
  vi.mocked(preCheckUpload).mockResolvedValue({ conflicts: [], candidates: [] })
  vi.mocked(initChunkedUpload).mockResolvedValue({
    uploadId: 'u1',
    chunkSize: 1024,
    totalChunks: 1,
  })
  vi.mocked(listUploadedChunks).mockResolvedValue([])
  vi.mocked(uploadChunk).mockResolvedValue({ chunkIndex: 0, status: 'success' })
  vi.mocked(completeChunkedUpload).mockResolvedValue({
    id: 'n1',
    name: 'hello.txt',
    type: 'file',
    size: '5',
    userId: '1',
    parentId: '0',
    storageSpaceId: '1',
    pathName: '/',
    status: 1,
  } as FileNodeVo)
})

describe('useBatchUpload', () => {
  it('should upload files without relativePath', async () => {
    const file = createFile('hello.txt', 'hello')
    const { uploadBatch } = useBatchUpload()

    await uploadBatch([{ file }], '0')

    expect(preCheckUpload).toHaveBeenCalledWith({
      fileName: 'hello.txt',
      size: 5,
      parentId: '0',
      relativePath: undefined,
      partialHash: 'mock-partial-hash',
    })
    expect(initChunkedUpload).toHaveBeenCalledWith('hello.txt', 5, '0', undefined)
  })

  it('should pass relativePath to preCheck and init', async () => {
    const file = createFile('main.java', 'class Main{}', 'project/src/main.java')
    const { uploadBatch } = useBatchUpload()

    await uploadBatch([{ file, relativePath: 'project/src/main.java' }], '0')

    expect(preCheckUpload).toHaveBeenCalledWith({
      fileName: 'main.java',
      size: 12,
      parentId: '0',
      relativePath: 'project/src/main.java',
      partialHash: 'mock-partial-hash',
    })
    expect(initChunkedUpload).toHaveBeenCalledWith(
      'main.java',
      12,
      '0',
      'project/src/main.java',
    )
  })

  it('should use defaultConflictStrategy and skip conflict popup', async () => {
    const file = createFile('hello.txt', 'hello')
    vi.mocked(preCheckUpload).mockResolvedValue({
      conflicts: [{
        sourceId: 'c1',
        sourceName: 'hello.txt',
        sourceType: 'file',
        existingId: 'e1',
        existingName: 'hello.txt',
        existingType: 'file',
      }],
      candidates: [],
    })

    const { uploadBatch } = useBatchUpload()
    const openConflict = vi.fn()

    await uploadBatch([{ file }], '0', {
      defaultConflictStrategy: 'keep',
      openConflict,
    })

    expect(openConflict).not.toHaveBeenCalled()
    expect(initChunkedUpload).toHaveBeenCalled()
  })

  it('should popup conflict modal when no default strategy', async () => {
    const file = createFile('hello.txt', 'hello')
    vi.mocked(preCheckUpload).mockResolvedValue({
      conflicts: [{
        sourceId: 'c1',
        sourceName: 'hello.txt',
        sourceType: 'file',
        existingId: 'e1',
        existingName: 'hello.txt',
        existingType: 'file',
      }],
      candidates: [],
    })

    const { uploadBatch } = useBatchUpload()
    const openConflict = vi.fn().mockResolvedValue({ generatedId: 'keep' as ConflictStrategy })

    await uploadBatch([{ file }], '0', { openConflict })

    expect(openConflict).toHaveBeenCalledTimes(1)
    const calledWith = openConflict.mock.calls[0][0]
    expect(calledWith).toHaveLength(1)
    expect(calledWith[0].sourceName).toBe('hello.txt')
    expect(calledWith[0].existingName).toBe('hello.txt')
    expect(initChunkedUpload).toHaveBeenCalled()
  })

  it('should skip upload when conflict strategy is skip', async () => {
    const file = createFile('hello.txt', 'hello')
    vi.mocked(preCheckUpload).mockResolvedValue({
      conflicts: [{
        sourceId: 'c1',
        sourceName: 'hello.txt',
        sourceType: 'file',
        existingId: 'e1',
        existingName: 'hello.txt',
        existingType: 'file',
      }],
      candidates: [],
    })

    const { uploadBatch } = useBatchUpload()

    await uploadBatch([{ file }], '0', {
      defaultConflictStrategy: 'skip',
    })

    expect(initChunkedUpload).not.toHaveBeenCalled()
  })
})
