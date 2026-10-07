import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick, ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { useFileList } from './useFileList'
import { ApiError } from '@/api/errors'
import type { FileNodeVo } from '@/types/file'
import type { ShareCreateRequest } from '@/types/share'

const mockFetchFilePage = vi.fn()
const mockFetchFilesByIds = vi.fn()
const mockDeleteToTrash = vi.fn()
const mockDownloadBatchFiles = vi.fn()
const mockUploadBatch = vi.fn()
const mockCreateShare = vi.fn()
const mockUpdateShare = vi.fn()
const mockGetCurrentUser = vi.fn()
const mockLookupMediaItemByFileNode = vi.fn()
const mockRouterPush = vi.fn()
const mockRouterReplace = vi.fn()
const mockCurrentRoute = ref<{ query: Record<string, string> }>({ query: {} })

vi.mock('@/api/file', () => ({
  fetchFilePage: (...args: unknown[]) => mockFetchFilePage(...args),
  fetchFilesByIds: (...args: unknown[]) => mockFetchFilesByIds(...args),
  deleteToTrash: (...args: unknown[]) => mockDeleteToTrash(...args),
  downloadBatchFiles: (...args: unknown[]) => mockDownloadBatchFiles(...args),
}))

vi.mock('@/api/media', () => ({
  lookupMediaItemByFileNode: (...args: unknown[]) => mockLookupMediaItemByFileNode(...args),
}))

vi.mock('@/router', () => ({
  default: {
    push: (...args: unknown[]) => mockRouterPush(...args),
    replace: (...args: unknown[]) => mockRouterReplace(...args),
    get currentRoute() {
      return mockCurrentRoute
    },
  },
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
    mockFetchFilesByIds.mockResolvedValue([])
    mockCurrentRoute.value = { query: {} }
    localStorage.clear()
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

  it('formats empty folder displaySize as 0', async () => {
    const folder = buildFileNode({ name: 'empty', type: 'folder', size: '0' })
    const list = await createList([folder])

    expect(list.displayFiles.value[0].displaySize).toBe('0')
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

  it('persists sort to localStorage with correct JSON', async () => {
    const list = await createList([])

    await list.setSortField('size')
    expect(list.sortOrder.value).toBe('desc')
    expect(JSON.parse(localStorage.getItem('file-list-sort')!)).toEqual({
      sortField: 'size',
      sortOrder: 'desc',
    })

    await list.toggleSortOrder()
    expect(JSON.parse(localStorage.getItem('file-list-sort')!)).toEqual({
      sortField: 'size',
      sortOrder: 'asc',
    })
  })

  it('restores sort from localStorage on init and applies to first load', async () => {
    localStorage.setItem('file-list-sort', JSON.stringify({ sortField: 'name', sortOrder: 'asc' }))
    mockFetchFilePage.mockResolvedValue({ records: [] })

    const list = useFileList()
    await list.loadFiles()

    expect(list.sortField.value).toBe('name')
    expect(list.sortOrder.value).toBe('asc')
    expect(mockFetchFilePage).toHaveBeenCalledWith(
      expect.objectContaining({ sortField: 'name', sortOrder: 'asc' }),
    )
  })

  it('falls back to default sort on corrupted JSON without throwing', async () => {
    localStorage.setItem('file-list-sort', '{not-json')

    const list = useFileList()
    await list.loadFiles()

    expect(list.sortField.value).toBe('createTime')
    expect(list.sortOrder.value).toBe('desc')
    expect(mockFetchFilePage).toHaveBeenCalledWith(
      expect.objectContaining({ sortField: 'createTime', sortOrder: 'desc' }),
    )
  })

  it('falls back invalid field to default, keeps valid order', async () => {
    localStorage.setItem('file-list-sort', JSON.stringify({ sortField: 'unknown', sortOrder: 'asc' }))

    const list = useFileList()
    await list.loadFiles()

    expect(list.sortField.value).toBe('createTime')
    expect(list.sortOrder.value).toBe('asc')
  })

  it('falls back invalid order to default, keeps valid field', async () => {
    localStorage.setItem('file-list-sort', JSON.stringify({ sortField: 'name', sortOrder: 'sideways' }))

    const list = useFileList()
    await list.loadFiles()

    expect(list.sortField.value).toBe('name')
    expect(list.sortOrder.value).toBe('desc')
  })

  it('setSort sets field and order and triggers a single load', async () => {
    const list = await createList([])
    const initialCalls = mockFetchFilePage.mock.calls.length

    await list.setSort('size', 'asc')

    expect(list.sortField.value).toBe('size')
    expect(list.sortOrder.value).toBe('asc')
    expect(mockFetchFilePage).toHaveBeenCalledTimes(initialCalls + 1)
    expect(JSON.parse(localStorage.getItem('file-list-sort')!)).toEqual({
      sortField: 'size',
      sortOrder: 'asc',
    })
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

  it('opens share modal for a single file without mixed-source check and keeps selection', async () => {
    const remote = buildFileNode({ id: 'a', sourceType: 'remote', remoteMountId: 'm1' })
    const list = await createList([remote])

    list.openShareModalFor(remote)

    expect(list.shareOpen.value).toBe(true)
    expect(list.shareModalItems.value).toEqual([remote])
    expect(list.shareModalItemIds.value).toEqual(['a'])
    expect(list.selectedIds.value.size).toBe(0)
  })

  it('opens email share modal for a single file without touching selection', async () => {
    const node = buildFileNode({ id: 'a' })
    const list = await createList([node])

    list.openEmailShareModalFor(node)

    expect(list.emailShareOpen.value).toBe(true)
    expect(list.shareModalItems.value).toEqual([node])
    expect(list.selectedIds.value.size).toBe(0)
  })

  it('keeps selection after a single-file share is created', async () => {
    const a = buildFileNode({ id: 'a' })
    const b = buildFileNode({ id: 'b' })
    mockCreateShare.mockResolvedValue({ id: 's1' })

    const list = await createList([a, b])
    list.toggleSelect('b')
    list.openShareModalFor(a)
    await list.handleCreateShare({ name: 'share', fileNodeIds: ['a'] } as ShareCreateRequest)

    expect(list.shareOpen.value).toBe(false)
    expect(list.selectedIds.value.has('b')).toBe(true)
    // 单文件分享结束后目标覆盖被重置，回落到批量勾选
    expect(list.shareModalItems.value).toEqual([b])
  })

  it('keeps selection after a single-file email share is sent', async () => {
    const a = buildFileNode({ id: 'a' })
    const b = buildFileNode({ id: 'b' })

    const list = await createList([a, b])
    list.toggleSelect('b')
    list.openEmailShareModalFor(a)
    list.handleEmailShareSent()

    expect(list.emailShareOpen.value).toBe(false)
    expect(list.selectedIds.value.has('b')).toBe(true)
    expect(list.shareModalItems.value).toEqual([b])
  })

  it('batch share entry keeps clearing selection', async () => {
    const a = buildFileNode({ id: 'a' })
    mockCreateShare.mockResolvedValue({ id: 's1' })

    const list = await createList([a])
    list.toggleSelect('a')
    list.openShareModal()
    expect(list.shareModalItems.value).toEqual([a])

    await list.handleCreateShare({ name: 'share', fileNodeIds: ['a'] } as ShareCreateRequest)

    expect(list.selectedIds.value.size).toBe(0)
  })

  it('resets single-file share target when the modal closes', async () => {
    const node = buildFileNode({ id: 'a' })
    const list = await createList([node])

    list.openShareModalFor(node)
    list.closeShareModal()
    expect(list.shareOpen.value).toBe(false)
    expect(list.shareModalItems.value).toEqual([])

    list.openEmailShareModalFor(node)
    list.closeEmailShareModal()
    expect(list.emailShareOpen.value).toBe(false)
    expect(list.shareModalItems.value).toEqual([])
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

  describe('openPreview 视频入口分流', () => {
    it('视频已收录且 versionId 非空：跳转播放页并携带 versionId，不开预览弹窗', async () => {
      mockLookupMediaItemByFileNode.mockResolvedValue({ itemId: 'item-1', versionId: 'ver-1' })
      const video = buildFileNode({ id: 'v1', name: 'movie.mkv', mimeType: 'video/x-matroska' })
      const list = await createList([video])

      await list.openPreview(video)

      expect(mockLookupMediaItemByFileNode).toHaveBeenCalledWith('v1')
      expect(mockRouterPush).toHaveBeenCalledWith({
        name: 'MediaPlay',
        params: { id: 'item-1' },
        query: { versionId: 'ver-1' },
      })
      expect(list.previewOpen.value).toBe(false)
    })

    it('视频已收录 versionId 为 null：跳转播放页且 query 不含 versionId', async () => {
      mockLookupMediaItemByFileNode.mockResolvedValue({ itemId: 'other-1', versionId: null })
      const video = buildFileNode({ id: 'v2', name: 'clip.mp4', mimeType: 'video/mp4' })
      const list = await createList([video])

      await list.openPreview(video)

      expect(mockRouterPush).toHaveBeenCalledWith({
        name: 'MediaPlay',
        params: { id: 'other-1' },
        query: {},
      })
      expect(list.previewOpen.value).toBe(false)
    })

    it('未收录（ApiError 404）：跳转纯播放路由以文件名直放，不开预览弹窗', async () => {
      mockLookupMediaItemByFileNode.mockRejectedValue(new ApiError(404, '媒体条目不存在'))
      const video = buildFileNode({ id: 'v3', name: 'movie.mkv', mimeType: 'video/x-matroska' })
      const list = await createList([video])

      await list.openPreview(video)

      expect(mockRouterPush).toHaveBeenCalledWith({ name: 'MediaPlayFile', params: { fileNodeId: 'v3' } })
      expect(list.previewOpen.value).toBe(false)
    })

    it('反查网络异常：同样跳转纯播放路由，不开预览弹窗', async () => {
      mockLookupMediaItemByFileNode.mockRejectedValue(new Error('network error'))
      const video = buildFileNode({ id: 'v4', name: 'movie.mkv', mimeType: 'video/x-matroska' })
      const list = await createList([video])

      await list.openPreview(video)

      expect(mockRouterPush).toHaveBeenCalledWith({ name: 'MediaPlayFile', params: { fileNodeId: 'v4' } })
      expect(list.previewOpen.value).toBe(false)
    })

    it('非视频文件：直接打开预览弹窗且不调用反查', async () => {
      const image = buildFileNode({ id: 'p1', name: 'photo.png', mimeType: 'image/png' })
      const list = await createList([image])

      await list.openPreview(image)

      expect(mockLookupMediaItemByFileNode).not.toHaveBeenCalled()
      expect(mockRouterPush).not.toHaveBeenCalled()
      expect(list.previewOpen.value).toBe(true)
      expect(list.previewTarget.value).toEqual(image)
    })
  })

  describe('URL 路径记忆', () => {
    const rootCrumb = { id: '0', name: '全部文件' }
    // 后端根节点占位 id：根目录下的节点 parentId 是补零 base36 形式，前端虚拟根用 '0'
    const ROOT_PARENT_ID = '0000000000000'

    function buildFolder(id: string, name: string, parentId: string): FileNodeVo {
      return buildFileNode({ id, name, parentId, type: 'folder' })
    }

    function setRoutePath(path: string) {
      mockCurrentRoute.value = path ? { query: { path } } : { query: {} }
    }

    it('进入文件夹与逐级深入时 push 点号分隔的 id 链', async () => {
      const list = await createList([])

      list.enterFolder(buildFolder('a', 'docs', ROOT_PARENT_ID))
      expect(mockRouterPush).toHaveBeenLastCalledWith({ query: { path: 'a' } })

      list.enterFolder(buildFolder('b', 'sub', 'a'))
      expect(mockRouterPush).toHaveBeenLastCalledWith({ query: { path: 'a.b' } })
    })

    it('面包屑回根目录时 push 的 query 不含 path', async () => {
      const list = await createList([])

      list.enterFolder(buildFolder('a', 'docs', ROOT_PARENT_ID))
      expect(mockRouterPush).toHaveBeenLastCalledWith({ query: { path: 'a' } })

      list.navigateToBreadcrumb(0)
      expect(mockRouterPush).toHaveBeenLastCalledWith({ query: {} })
    })

    it('init 恢复有效 id 链：面包屑名称与当前目录均来自接口返回', async () => {
      // 接口不保证顺序，故意逆序返回以验证按链顺序重建
      mockFetchFilesByIds.mockResolvedValue([
        buildFolder('b', 'sub', 'a'),
        buildFolder('a', 'docs', ROOT_PARENT_ID),
      ])
      setRoutePath('a.b')

      const list = useFileList()
      await list.init()

      expect(mockFetchFilesByIds).toHaveBeenCalledWith(['a', 'b'])
      expect(list.breadcrumbStack.value).toEqual([
        rootCrumb,
        { id: 'a', name: 'docs' },
        { id: 'b', name: 'sub' },
      ])
      expect(list.currentParentId.value).toBe('b')
      expect(mockFetchFilePage).toHaveBeenLastCalledWith(expect.objectContaining({ parentId: 'b' }))
      expect(mockRouterReplace).not.toHaveBeenCalled()
    })

    it('init 链中一级缺失：静默回退根目录并 replace 清 path', async () => {
      mockFetchFilesByIds.mockResolvedValue([buildFolder('a', 'docs', ROOT_PARENT_ID)])
      setRoutePath('a.b')

      const list = useFileList()
      await list.init()

      expect(list.currentParentId.value).toBe('0')
      expect(list.breadcrumbStack.value).toEqual([rootCrumb])
      expect(mockRouterReplace).toHaveBeenCalledWith({ query: {} })
      expect(mockFetchFilePage).toHaveBeenLastCalledWith(expect.objectContaining({ parentId: '0' }))
    })

    it('init 链中一级为文件类型：回退根目录', async () => {
      mockFetchFilesByIds.mockResolvedValue([
        buildFileNode({ id: 'a', name: 'x.txt', parentId: ROOT_PARENT_ID }),
      ])
      setRoutePath('a')

      const list = useFileList()
      await list.init()

      expect(list.currentParentId.value).toBe('0')
      expect(mockRouterReplace).toHaveBeenCalledWith({ query: {} })
    })

    it('init 父子链断裂：回退根目录', async () => {
      mockFetchFilesByIds.mockResolvedValue([
        buildFolder('a', 'docs', ROOT_PARENT_ID),
        buildFolder('b', 'sub', 'x'),
      ])
      setRoutePath('a.b')

      const list = useFileList()
      await list.init()

      expect(list.currentParentId.value).toBe('0')
      expect(mockRouterReplace).toHaveBeenCalledWith({ query: {} })
    })

    it('init 链首节点已被移出根目录：回退根目录', async () => {
      mockFetchFilesByIds.mockResolvedValue([buildFolder('a', 'docs', 'other')])
      setRoutePath('a')

      const list = useFileList()
      await list.init()

      expect(list.currentParentId.value).toBe('0')
      expect(mockRouterReplace).toHaveBeenCalledWith({ query: {} })
    })

    it('前进/后退切换 path：状态跟随 URL 且不再 push', async () => {
      mockFetchFilesByIds.mockResolvedValue([
        buildFolder('a', 'docs', ROOT_PARENT_ID),
        buildFolder('b', 'sub', 'a'),
      ])
      const list = useFileList()
      await list.loadFiles()

      setRoutePath('a.b')
      await nextTick()
      await flushPromises()

      expect(list.currentParentId.value).toBe('b')
      expect(list.breadcrumbStack.value.map((crumb) => crumb.name)).toEqual([
        '全部文件',
        'docs',
        'sub',
      ])

      setRoutePath('')
      await nextTick()
      await flushPromises()

      expect(list.currentParentId.value).toBe('0')
      expect(list.breadcrumbStack.value).toEqual([rootCrumb])
      expect(mockRouterPush).not.toHaveBeenCalled()
    })
  })
})
