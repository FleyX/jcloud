import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useFileList } from './useFileList'
import type { FileNodeVo } from '@/types/file'
import type { ShareCreateRequest } from '@/types/share'

const mockFetchFilePage = vi.fn()
const mockDeleteToTrash = vi.fn()
const mockDownloadBatchFiles = vi.fn()
const mockUploadBatch = vi.fn()
const mockCreateShare = vi.fn()
const mockUpdateShare = vi.fn()
const mockGetCurrentUser = vi.fn()

vi.mock('@/api/file', () => ({
  fetchFilePage: (...args: unknown[]) => mockFetchFilePage(...args),
  deleteToTrash: (...args: unknown[]) => mockDeleteToTrash(...args),
  downloadBatchFiles: (...args: unknown[]) => mockDownloadBatchFiles(...args),
}))

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  getCurrentUser: (...args: unknown[]) => mockGetCurrentUser(...args),
}))

vi.mock('@/api/share', () => ({
  createShare: (...args: unknown[]) => mockCreateShare(...args),
  updateShare: (...args: unknown[]) => mockUpdateShare(...args),
}))

vi.mock('@/composables/useBatchUpload', () => ({
  useBatchUpload: () => ({ uploadBatch: mockUploadBatch }),
}))

vi.mock('@/store/confirm', () => ({
  useConfirmStore: () => ({
    open: vi.fn().mockResolvedValue(true),
  }),
}))

function buildFileNode(overrides: Partial<FileNodeVo> = {}): FileNodeVo {
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
    mimeType: 'text/plain',
    ...overrides,
  }
}

async function createList(records: FileNodeVo[] = []) {
  mockFetchFilePage.mockResolvedValue({ records })
  const list = useFileList()
  await list.loadFiles()
  return list
}

describe('useFileList', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockFetchFilePage.mockResolvedValue({ records: [] })
  })

  it('loads files', async () => {
    const node = buildFileNode()
    const list = await createList([node])

    expect(mockFetchFilePage).toHaveBeenCalledWith(expect.objectContaining({ parentId: '0' }))
    expect(list.files.value).toHaveLength(1)
  })

  it('exposes displayFiles with iconType and displaySize', async () => {
    const node = buildFileNode({ name: 'photo.png', mimeType: 'image/png', size: '2048' })
    const list = await createList([node])

    const display = list.displayFiles.value[0]
    expect(display.iconType).toBe('image')
    expect(display.displaySize).toBe('2.00 KB')
    expect(display.selected).toBe(false)
  })

  it('formats folder displaySize from persisted size', async () => {
    const folder = buildFileNode({ name: 'docs', type: 'folder', size: '2048' })
    const list = await createList([folder])

    const display = list.displayFiles.value[0]
    expect(display.type).toBe('folder')
    expect(display.displaySize).toBe('2.00 KB')
  })

  it('toggles selection', async () => {
    const a = buildFileNode({ id: 'a' })
    const b = buildFileNode({ id: 'b' })
    const list = await createList([a, b])

    list.toggleSelect('a')
    expect(list.selectedIds.value.has('a')).toBe(true)
    expect(list.selectedFiles.value).toHaveLength(1)
    expect(list.isAllSelected.value).toBe(false)

    list.toggleSelectAll()
    expect(list.isAllSelected.value).toBe(true)

    list.clearSelection()
    expect(list.selectedIds.value.size).toBe(0)
  })

  it('navigates folders and breadcrumbs', async () => {
    const child = buildFileNode({ id: '1', name: 'child', type: 'folder' })
    const list = await createList([child])

    list.enterFolder(child)
    await list.loadFiles()

    expect(list.currentParentId.value).toBe('1')
    expect(list.breadcrumbStack.value).toHaveLength(2)

    list.navigateToBreadcrumb(0)
    expect(list.currentParentId.value).toBe('0')
  })

  it('searches and clears search', async () => {
    const list = await createList([])

    list.keyword.value = 'report'
    await list.handleSearch()
    expect(mockFetchFilePage).toHaveBeenLastCalledWith(expect.objectContaining({ name: 'report' }))

    await list.clearSearch()
    expect(list.keyword.value).toBe('')
    expect(mockFetchFilePage).toHaveBeenLastCalledWith(expect.objectContaining({ name: '' }))
  })

  it('auto-searches on keyword input with debounce', async () => {
    const list = await createList([])
    vi.useFakeTimers()
    try {
      const callsBefore = mockFetchFilePage.mock.calls.length

      list.keyword.value = 'bi'
      list.keyword.value = 'big'
      await vi.advanceTimersByTimeAsync(350)

      // 防抖窗口内连续输入只触发一次查询
      expect(mockFetchFilePage).toHaveBeenCalledTimes(callsBefore + 1)
      expect(mockFetchFilePage).toHaveBeenLastCalledWith(expect.objectContaining({ name: 'big' }))
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not auto-search when keyword is cleared programmatically', async () => {
    const list = await createList([])
    vi.useFakeTimers()
    try {
      await list.clearSearch()
      const callsBefore = mockFetchFilePage.mock.calls.length

      list.keyword.value = 'temp'
      await vi.advanceTimersByTimeAsync(350)
      await list.clearSearch()
      await vi.advanceTimersByTimeAsync(350)

      // clearSearch 只产生显式的一次 loadFiles，不会触发 watcher 的二次查询
      expect(mockFetchFilePage).toHaveBeenCalledTimes(callsBefore + 2)
      expect(mockFetchFilePage).toHaveBeenLastCalledWith(expect.objectContaining({ name: '' }))
    } finally {
      vi.useRealTimers()
    }
  })

  it('toggles sort for PC', async () => {
    const list = await createList([])
    const initialCalls = mockFetchFilePage.mock.calls.length

    await list.toggleSort('name')
    expect(list.sortField.value).toBe('name')
    expect(list.sortOrder.value).toBe('asc')
    expect(mockFetchFilePage).toHaveBeenCalledTimes(initialCalls + 1)

    await list.toggleSort('name')
    expect(list.sortOrder.value).toBe('desc')
  })

  it('sets sort field and toggles order for mobile', async () => {
    const list = await createList([])

    await list.setSortField('size')
    expect(list.sortField.value).toBe('size')

    await list.toggleSortOrder()
    expect(list.sortOrder.value).toBe('asc')
  })

  it('deletes single file after confirm', async () => {
    const node = buildFileNode({ id: 'a', name: 'x.txt' })
    mockDeleteToTrash.mockResolvedValue([])

    const list = await createList([node])
    await list.handleDelete(node)

    expect(mockDeleteToTrash).toHaveBeenCalledWith({ ids: ['a'] })
  })

  it('refreshes user info after delete (debounced)', async () => {
    const node = buildFileNode({ id: 'a', name: 'x.txt' })
    mockDeleteToTrash.mockResolvedValue([])
    mockGetCurrentUser.mockResolvedValue({
      token: 't',
      refreshToken: 'r',
      deviceId: 'd',
      userInfo: { id: '1', username: 'u', status: 1, isAdmin: false, roles: [] },
      resources: [],
      initialized: true,
    })

    const list = await createList([node])
    vi.useFakeTimers()
    try {
      await list.handleDelete(node)
      expect(mockGetCurrentUser).not.toHaveBeenCalled()
      await vi.advanceTimersByTimeAsync(800)
      expect(mockGetCurrentUser).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('batch downloads selected files', async () => {
    const a = buildFileNode({ id: 'a' })
    const b = buildFileNode({ id: 'b' })
    mockDownloadBatchFiles.mockResolvedValue(undefined)

    const list = await createList([a, b])
    list.toggleSelect('a')
    list.toggleSelect('b')
    await list.handleBatchDownload()

    expect(mockDownloadBatchFiles).toHaveBeenCalledWith(
      ['a', 'b'],
      'archive.zip',
      expect.any(Function),
    )
  })

  it('prevents share when selection has mixed source', async () => {
    const local = buildFileNode({ id: 'a', sourceType: 'local' })
    const remote = buildFileNode({ id: 'b', sourceType: 'remote', remoteMountId: 'm1' })

    const list = await createList([local, remote])
    list.toggleSelect('a')
    list.toggleSelect('b')
    list.openShareModal()

    expect(list.shareOpen.value).toBe(false)
  })

  it('creates share and clears selection', async () => {
    const node = buildFileNode({ id: 'a' })
    mockCreateShare.mockResolvedValue({ id: 's1' })

    const list = await createList([node])
    list.toggleSelect('a')
    await list.handleCreateShare({ name: 'share', fileNodeIds: ['a'] } as ShareCreateRequest)

    expect(mockCreateShare).toHaveBeenCalledWith(expect.objectContaining({ name: 'share' }))
    expect(list.shareOpen.value).toBe(false)
    expect(list.selectedIds.value.size).toBe(0)
  })

  it('uploads files through useBatchUpload', async () => {
    const file = new File(['x'], 'x.txt')

    const list = await createList([])
    await list.uploadFiles([file])

    expect(mockUploadBatch).toHaveBeenCalledWith(
      [{ file }],
      '0',
      expect.objectContaining({ onComplete: expect.any(Function), openConflict: expect.any(Function) }),
    )
  })

  it('uploads folder with relative paths', async () => {
    const file = new File(['x'], 'x.txt')
    Object.defineProperty(file, 'webkitRelativePath', { value: 'dir/x.txt' })

    const list = await createList([])
    await list.uploadFolder([file])

    expect(mockUploadBatch).toHaveBeenCalledWith(
      [{ file, relativePath: 'dir/x.txt' }],
      '0',
      expect.objectContaining({ defaultConflictStrategy: 'keep' }),
    )
  })

  it('resolves upload conflicts via modal callbacks', async () => {
    const list = await createList([])
    const conflictPromise = list.openUploadConflict([
      { sourceId: 'c1', sourceName: 'a.txt', sourceType: 'file', existingId: 'e1', existingName: 'a.txt', existingType: 'file' },
    ])

    expect(list.uploadConflictOpen.value).toBe(true)
    list.handleUploadConflictConfirm({ c1: 'overwrite' })

    const result = await conflictPromise
    expect(result).toEqual({ c1: 'overwrite' })
    expect(list.uploadConflictOpen.value).toBe(false)
  })

  it('cancels upload conflicts', async () => {
    const list = await createList([])
    const conflictPromise = list.openUploadConflict([])
    list.handleUploadConflictCancel()

    const result = await conflictPromise
    expect(result).toBeNull()
  })
})
