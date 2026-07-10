import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useFileOperations } from './useFileOperations'
import type { FileNodeVo } from '@/types/file'

const mockCreateFolder = vi.fn()
const mockRenameFile = vi.fn()
const mockSuccess = vi.fn()

vi.mock('@/api/file', () => ({
  createFolder: (...args: unknown[]) => mockCreateFolder(...args),
  renameFile: (...args: unknown[]) => mockRenameFile(...args),
}))

vi.mock('@/store/notification', () => ({
  useNotificationStore: () => ({ success: mockSuccess }),
}))

function buildFile(): FileNodeVo {
  return {
    id: '1',
    userId: '1',
    parentId: '0',
    name: 'report.txt',
    type: 'file',
    size: '1024',
    storageSpaceId: '1',
    pathName: '/',
    status: 1,
  }
}

describe('useFileOperations', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('opens and closes create folder modal', () => {
    const loadFiles = vi.fn().mockResolvedValue(undefined)
    const ops = useFileOperations(loadFiles)

    expect(ops.createFolderOpen.value).toBe(false)
    ops.openCreateFolder()
    expect(ops.createFolderOpen.value).toBe(true)
  })

  it('creates folder and reloads', async () => {
    const loadFiles = vi.fn().mockResolvedValue(undefined)
    mockCreateFolder.mockResolvedValue({})

    const ops = useFileOperations(loadFiles)
    ops.openCreateFolder()
    await ops.handleCreateFolder('new-folder', '0')

    expect(mockCreateFolder).toHaveBeenCalledWith({ parentId: '0', name: 'new-folder' })
    expect(mockSuccess).toHaveBeenCalledWith('文件夹创建成功')
    expect(ops.createFolderOpen.value).toBe(false)
    expect(loadFiles).toHaveBeenCalled()
  })

  it('opens rename modal with target', () => {
    const loadFiles = vi.fn().mockResolvedValue(undefined)
    const file = buildFile()

    const ops = useFileOperations(loadFiles)
    ops.openRename(file)

    expect(ops.renameOpen.value).toBe(true)
    expect(ops.renameTarget.value).toStrictEqual(file)
  })

  it('renames file and reloads', async () => {
    const loadFiles = vi.fn().mockResolvedValue(undefined)
    mockRenameFile.mockResolvedValue({})

    const ops = useFileOperations(loadFiles)
    await ops.handleRename('1', 'renamed.txt')

    expect(mockRenameFile).toHaveBeenCalledWith({ id: '1', newName: 'renamed.txt' })
    expect(mockSuccess).toHaveBeenCalledWith('重命名成功')
    expect(ops.renameOpen.value).toBe(false)
    expect(loadFiles).toHaveBeenCalled()
  })

  it('survives api errors without throwing', async () => {
    const loadFiles = vi.fn().mockResolvedValue(undefined)
    mockCreateFolder.mockRejectedValue(new Error('fail'))

    const ops = useFileOperations(loadFiles)
    await expect(ops.handleCreateFolder('x', '0')).resolves.not.toThrow()
    expect(loadFiles).not.toHaveBeenCalled()
  })
})
