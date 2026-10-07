import { computed, ref, watch } from 'vue'
import {
  deleteToTrash,
  downloadBatchFiles,
  fetchFilePage,
  fetchFilesByIds,
} from '@/api/file'
import { lookupMediaItemByFileNode } from '@/api/media'
import { createShare, updateShare } from '@/api/share'
import { useBatchUpload } from '@/composables/useBatchUpload'
import router from '@/router'
import { useConfirmStore } from '@/store/confirm'
import { useNotificationStore } from '@/store/notification'
import { useTransferStore } from '@/store/transfer'
import { useTransferTaskStore } from '@/store/transferTask'
import { useUserStore } from '@/store/user'
import { formatSize, inferFileType } from '@/utils/fileDisplay'
import { resolvePreviewCategory } from '@/utils/previewCategory'
import type { FileDisplayType } from '@/utils/fileDisplay'
import type {
  ConflictItemVo,
  ConflictStrategy,
  FileNodeVo,
  FileSortField,
  FileSortOrder,
  OperationResultVo,
} from '@/types/file'
import type { ShareCreateRequest, ShareDetailVo, ShareUpdateRequest } from '@/types/share'
import type { LocationQueryRaw } from 'vue-router'

export interface DisplayFileNode extends FileNodeVo {
  iconType: FileDisplayType
  displaySize: string
  selected: boolean
}

export interface UseFileListOptions {
  rootName?: string
  initialParentId?: string
}

/** 文件列表排序偏好存储 key（全局单一 key，值结构为 {sortField, sortOrder}） */
const FILE_LIST_SORT_KEY = 'file-list-sort'
/** 合法排序字段集合 */
const SORT_FIELDS: readonly FileSortField[] = ['name', 'size', 'createTime']
/** 合法排序方向集合 */
const SORT_ORDERS: readonly FileSortOrder[] = ['asc', 'desc']

function hasMixedSource(nodes: FileNodeVo[]): boolean {
  if (nodes.length < 2) return false
  const firstSource = nodes[0].sourceType || 'local'
  const firstMountId = nodes[0].remoteMountId
  return nodes.some((node) => {
    const source = node.sourceType || 'local'
    if (source !== firstSource) return true
    if (source === 'remote' && node.remoteMountId !== firstMountId) return true
    return false
  })
}

/**
 * 文件列表页面级状态与行为组合式函数。
 * 抽象 PC / 移动端全部文件页共用逻辑：加载、搜索、排序、选择、上传冲突、
 * 预览、分享、移动/复制、删除、批量下载。
 */
export function useFileList(options: UseFileListOptions = {}) {
  const { rootName = '全部文件', initialParentId = '0' } = options

  const notificationStore = useNotificationStore()
  const confirmStore = useConfirmStore()
  const transferStore = useTransferStore()
  const userStore = useUserStore()
  const { uploadBatch } = useBatchUpload()

  const files = ref<FileNodeVo[]>([])
  const loading = ref(false)
  const keyword = ref('')
  const selectedIds = ref<Set<string>>(new Set())
  const currentParentId = ref(initialParentId)
  const breadcrumbStack = ref<Array<{ id: string; name: string }>>([
    { id: initialParentId, name: rootName },
  ])

  const sorted = restoreSort()
  const sortField = ref<FileSortField>(sorted.field)
  const sortOrder = ref<FileSortOrder>(sorted.order)

  const previewOpen = ref(false)
  const previewTarget = ref<FileNodeVo | null>(null)

  const moveCopyOpen = ref(false)
  const moveCopyType = ref<'move' | 'copy'>('move')
  const moveCopyTargets = ref<FileNodeVo[]>([])

  const shareOpen = ref(false)
  const shareEditTarget = ref<ShareDetailVo | undefined>(undefined)
  const emailShareOpen = ref(false)
  /** 单文件行内分享的目标覆盖；为 null 表示走批量勾选流程 */
  const shareTargets = ref<FileNodeVo[] | null>(null)

  const uploadConflictOpen = ref(false)
  const uploadConflicts = ref<ConflictItemVo[]>([])
  let uploadConflictResolve: ((strategies: Record<string, ConflictStrategy> | null) => void) | null = null

  const folders = computed(() => files.value.filter((file) => file.type === 'folder'))
  const selectedFiles = computed(() => files.value.filter((file) => selectedIds.value.has(file.id)))
  const shareModalItems = computed<FileNodeVo[]>(() => shareTargets.value ?? selectedFiles.value)
  const shareModalItemIds = computed<string[]>(() => shareModalItems.value.map((file) => file.id))
  const isAllSelected = computed(() => files.value.length > 0 && selectedIds.value.size === files.value.length)
  const hasMixedSelection = computed(() => hasMixedSource(selectedFiles.value))
  const isSearching = computed(() => keyword.value.trim().length > 0)

  const displayFiles = computed<DisplayFileNode[]>(() =>
    files.value.map((file) => ({
      ...file,
      iconType: inferFileType(file),
      displaySize: formatSize(file.size),
      selected: selectedIds.value.has(file.id),
    })),
  )

  const transferTaskStore = useTransferTaskStore()
  // 跨来源传输任务到达终态后刷新文件列表
  watch(() => transferTaskStore.finishedTick, () => {
    loadFiles()
  })

  /**
   * 从 localStorage 恢复排序偏好，逐项校验字段与方向，任一非法或异常即回退默认。
   */
  function restoreSort(): { field: FileSortField; order: FileSortOrder } {
    try {
      const saved = JSON.parse(localStorage.getItem(FILE_LIST_SORT_KEY) || 'null') as {
        sortField?: FileSortField
        sortOrder?: FileSortOrder
      } | null
      const field = saved?.sortField
      const order = saved?.sortOrder
      return {
        field: SORT_FIELDS.includes(field as FileSortField) ? (field as FileSortField) : 'createTime',
        order: SORT_ORDERS.includes(order as FileSortOrder) ? (order as FileSortOrder) : 'desc',
      }
    } catch {
      return { field: 'createTime', order: 'desc' }
    }
  }

  function persistSort() {
    localStorage.setItem(
      FILE_LIST_SORT_KEY,
      JSON.stringify({ sortField: sortField.value, sortOrder: sortOrder.value }),
    )
  }

  /** URL query 中承载目录 id 链的键名 */
  const PATH_QUERY_KEY = 'path'

  /** 当前面包屑对应的 id 链签名（根目录为空串） */
  function pathSignature(): string {
    return breadcrumbStack.value
      .slice(1)
      .map((crumb) => crumb.id)
      .join('.')
  }

  /** 回退到根目录初始状态 */
  function resetToRoot() {
    currentParentId.value = initialParentId
    breadcrumbStack.value = [{ id: initialParentId, name: rootName }]
  }

  /**
   * 将 id 链写入 URL query：保留既有 query，链为空（根目录）时删除 path 键。
   * 默认 push 产生历史记录；replace 用于修正失效链接。
   */
  function writePathQuery(chain: string, replace = false): void {
    const query: LocationQueryRaw = { ...router.currentRoute.value.query }
    if (chain) {
      query[PATH_QUERY_KEY] = chain
    } else {
      delete query[PATH_QUERY_KEY]
    }
    const location = { query }
    void (replace ? router.replace(location) : router.push(location))
  }

  /**
   * 节点是否直接位于虚拟根目录下。
   * 前端虚拟根 id 为 '0'，后端节点中存的是补零 base36 形式的根 id（0000000000000），两者等价。
   */
  function isRootChild(parentId: string): boolean {
    return parentId === initialParentId || /^0+$/.test(parentId)
  }

  /**
   * 按 id 链顺序校验节点：数量、类型、父子衔接任一项不满足即返回 null。
   * 后端不保证返回顺序，先按 id 建映射再按链顺序取。
   */
  function buildStackFromChain(
    chain: string[],
    nodes: FileNodeVo[],
  ): Array<{ id: string; name: string }> | null {
    if (nodes.length !== chain.length) return null
    const nodeMap = new Map(nodes.map((node) => [node.id, node]))
    const stack: Array<{ id: string; name: string }> = [{ id: initialParentId, name: rootName }]
    // null 表示当前是链首，其父节点必须是虚拟根
    let expectedParentId: string | null = null
    for (const id of chain) {
      const node = nodeMap.get(id)
      if (!node || node.type !== 'folder') return null
      const parentMatched = expectedParentId === null
        ? isRootChild(node.parentId)
        : node.parentId === expectedParentId
      if (!parentMatched) return null
      stack.push({ id: node.id, name: node.name })
      expectedParentId = node.id
    }
    return stack
  }

  /**
   * 按 URL path 恢复目录状态：链全部有效则重建面包屑，任一级失效静默回退根目录并修正 URL。
   */
  async function restoreFromUrl(): Promise<void> {
    const raw = router.currentRoute.value.query[PATH_QUERY_KEY]
    const chain = typeof raw === 'string' ? raw.split('.').filter((id) => id.length > 0) : []
    if (chain.length === 0) {
      resetToRoot()
      return loadFiles()
    }
    const nodes = await fetchFilesByIds(chain)
    const stack = buildStackFromChain(chain, nodes)
    if (stack) {
      breadcrumbStack.value = stack
      currentParentId.value = stack[stack.length - 1].id
    } else {
      resetToRoot()
      writePathQuery('', true)
    }
    return loadFiles()
  }

  /** 页面初始化：优先按 URL path 恢复目录，无 path 时加载根目录 */
  function init(): Promise<void> {
    return restoreFromUrl()
  }

  // 浏览器前进/后退：URL path 与当前面包屑不一致时重建目录状态（自身 push 引起的变化签名一致，自然跳过）
  watch(
    () => router.currentRoute.value.query[PATH_QUERY_KEY],
    (value) => {
      if ((typeof value === 'string' ? value : '') === pathSignature()) return
      void restoreFromUrl()
    },
  )

  async function loadFiles() {
    loading.value = true
    try {
      const res = await fetchFilePage({
        parentId: currentParentId.value,
        name: keyword.value,
        sortField: sortField.value,
        sortOrder: sortOrder.value,
        pageNum: 1,
        pageSize: 100,
      })
      files.value = res.records
      selectedIds.value.clear()
    } finally {
      loading.value = false
    }
  }

  function setSortField(field: FileSortField) {
    sortField.value = field
    persistSort()
    return loadFiles()
  }

  function toggleSortOrder() {
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
    persistSort()
    return loadFiles()
  }

  /**
   * PC 端表头排序交互：
   * 同字段切换方向，不同字段默认升序。
   */
  function toggleSort(field: FileSortField) {
    if (sortField.value === field) {
      sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
    } else {
      sortField.value = field
      sortOrder.value = 'asc'
    }
    persistSort()
    return loadFiles()
  }

  /**
   * 同时设置排序字段与方向，仅触发一次列表加载。
   * 供排序弹窗确认回调等一次决定字段与方向的场景使用。
   */
  function setSort(field: FileSortField, order: FileSortOrder) {
    sortField.value = field
    sortOrder.value = order
    persistSort()
    return loadFiles()
  }

  function handleSearch() {
    return loadFiles()
  }

  // 搜索关键词实时过滤：输入防抖 300ms 后自动查询
  let searchTimer: ReturnType<typeof setTimeout> | null = null
  let suppressKeywordWatch = false
  watch(keyword, () => {
    if (suppressKeywordWatch) {
      suppressKeywordWatch = false
      return
    }
    if (searchTimer) clearTimeout(searchTimer)
    searchTimer = setTimeout(() => {
      void loadFiles()
    }, 300)
  })

  /** 程序化清空关键词（随后会显式调用 loadFiles），避免触发重复查询 */
  function resetKeywordSilently() {
    if (keyword.value !== '') suppressKeywordWatch = true
    keyword.value = ''
  }

  function clearSearch() {
    resetKeywordSilently()
    return loadFiles()
  }

  function enterFolder(file: FileNodeVo) {
    if (file.type !== 'folder') return
    currentParentId.value = file.id
    breadcrumbStack.value.push({ id: file.id, name: file.name })
    resetKeywordSilently()
    writePathQuery(pathSignature())
    return loadFiles()
  }

  function navigateToBreadcrumb(index: number) {
    breadcrumbStack.value = breadcrumbStack.value.slice(0, index + 1)
    currentParentId.value = breadcrumbStack.value[index].id
    resetKeywordSilently()
    writePathQuery(pathSignature())
    return loadFiles()
  }

  function toggleSelect(id: string) {
    if (selectedIds.value.has(id)) {
      selectedIds.value.delete(id)
    } else {
      selectedIds.value.add(id)
    }
  }

  function toggleSelectAll() {
    if (isAllSelected.value) {
      selectedIds.value.clear()
    } else {
      selectedIds.value = new Set(files.value.map((file) => file.id))
    }
  }

  function clearSelection() {
    selectedIds.value.clear()
  }

  /**
   * 打开文件：视频文件先反查媒体收录，已收录跳转影视独立播放页（携带 versionId 时从点击的文件起播）；
   * 未收录或反查失败跳转纯播放页以文件名直放（对媒体数据零写入）；非视频类型维持原弹窗行为。
   */
  async function openPreview(file: FileNodeVo) {
    if (file.type !== 'file') return
    if (resolvePreviewCategory(file.mimeType, file.name) === 'video') {
      try {
        const { itemId, versionId } = await lookupMediaItemByFileNode(file.id)
        router.push({
          name: 'MediaPlay',
          params: { id: itemId },
          query: versionId ? { versionId } : {},
        })
        return
      } catch {
        // 未收录或反查失败：统一进纯播放模式直放
        router.push({ name: 'MediaPlayFile', params: { fileNodeId: file.id } })
        return
      }
    }
    previewTarget.value = file
    previewOpen.value = true
  }

  function openMoveCopy(type: 'move' | 'copy', targets: FileNodeVo[]) {
    moveCopyType.value = type
    moveCopyTargets.value = targets
    moveCopyOpen.value = true
  }

  async function handleMoveCopyResult(results: OperationResultVo[]) {
    const successCount = results.filter((r) => r.status === 'success').length
    if (successCount > 0) {
      await loadFiles()
    }
  }

  async function handleDelete(file: FileNodeVo) {
    const confirmed = await confirmStore.open({
      title: '删除文件',
      message: `确定将 "${file.name}" 移动到回收站吗？`,
      confirmText: '删除',
      type: 'danger',
    })
    if (!confirmed) return
    await deleteToTrash({ ids: [file.id] })
    // 删除占用已用空间，防抖刷新用户容量信息
    userStore.scheduleUserInfoRefresh()
    notificationStore.success('已移动到回收站')
    await loadFiles()
  }

  async function handleBatchDelete() {
    const targets = selectedFiles.value
    if (targets.length === 0) return
    const confirmed = await confirmStore.open({
      title: '批量删除',
      message: `确定将选中的 ${targets.length} 项移动到回收站吗？`,
      confirmText: '删除',
      type: 'danger',
    })
    if (!confirmed) return
    await deleteToTrash({ ids: targets.map((f) => f.id) })
    // 删除占用已用空间，防抖刷新用户容量信息
    userStore.scheduleUserInfoRefresh()
    notificationStore.success('已移动到回收站')
    await loadFiles()
  }

  async function handleBatchDownload() {
    const targets = selectedFiles.value
    if (targets.length === 0) return
    const taskId = `dl-${Date.now()}`
    transferStore.addDownloadTask({ taskId, fileName: 'archive.zip' })
    try {
      await downloadBatchFiles(
        targets.map((f) => f.id),
        'archive.zip',
        (progress) => transferStore.updateDownloadProgress(taskId, progress),
      )
      transferStore.completeDownloadTask(taskId)
      notificationStore.success('下载完成')
    } catch (err) {
      const message = err instanceof Error ? err.message : '下载失败'
      transferStore.failDownloadTask(taskId, message)
      // 请求层已统一提示
    }
  }

  function openShareModal() {
    shareTargets.value = null
    const targets = selectedFiles.value
    if (targets.length === 0) return
    if (hasMixedSource(targets)) {
      notificationStore.error('分享不能同时包含本地与远程文件')
      return
    }
    shareEditTarget.value = undefined
    shareOpen.value = true
  }

  function openShareModalFor(file: FileNodeVo) {
    shareTargets.value = [file]
    shareEditTarget.value = undefined
    shareOpen.value = true
  }

  function openEmailShareModal() {
    shareTargets.value = null
    const targets = selectedFiles.value
    if (targets.length === 0) return
    if (hasMixedSource(targets)) {
      notificationStore.error('分享不能同时包含本地与远程文件')
      return
    }
    emailShareOpen.value = true
  }

  function openEmailShareModalFor(file: FileNodeVo) {
    shareTargets.value = [file]
    emailShareOpen.value = true
  }

  function closeShareModal() {
    shareOpen.value = false
    shareTargets.value = null
  }

  function closeEmailShareModal() {
    emailShareOpen.value = false
    shareTargets.value = null
  }

  function handleEmailShareSent() {
    const isBatch = shareTargets.value === null
    emailShareOpen.value = false
    shareTargets.value = null
    if (isBatch) selectedIds.value.clear()
  }

  async function handleCreateShare(payload: ShareCreateRequest) {
    const isBatch = shareTargets.value === null
    const share = await createShare(payload)
    shareOpen.value = false
    shareTargets.value = null
    if (isBatch) selectedIds.value.clear()
    notificationStore.success('分享创建成功')
    return share
  }

  async function handleUpdateShare(payload: ShareUpdateRequest) {
    if (!shareEditTarget.value) return
    await updateShare(shareEditTarget.value.id, payload)
    shareOpen.value = false
    notificationStore.success('分享已更新')
  }

  function handleShareConfirm(payload: ShareCreateRequest | ShareUpdateRequest) {
    if (shareEditTarget.value) {
      return handleUpdateShare(payload as ShareUpdateRequest)
    }
    return handleCreateShare(payload as ShareCreateRequest)
  }

  function openUploadConflict(conflicts: ConflictItemVo[]): Promise<Record<string, ConflictStrategy> | null> {
    return new Promise((resolve) => {
      uploadConflicts.value = conflicts
      uploadConflictResolve = resolve
      uploadConflictOpen.value = true
    })
  }

  function handleUploadConflictConfirm(strategies: Record<string, ConflictStrategy>) {
    uploadConflictOpen.value = false
    uploadConflictResolve?.(strategies)
    uploadConflictResolve = null
  }

  function handleUploadConflictCancel() {
    uploadConflictOpen.value = false
    uploadConflictResolve?.(null)
    uploadConflictResolve = null
  }

  async function uploadFiles(rawFiles: File[]) {
    if (rawFiles.length === 0) return
    const batchFiles = rawFiles.map((file) => ({ file }))
    await uploadBatch(batchFiles, currentParentId.value, {
      onComplete: loadFiles,
      openConflict: openUploadConflict,
    })
  }

  async function uploadFolder(rawFiles: File[]) {
    if (rawFiles.length === 0) return
    const batchFiles = rawFiles.map((file) => ({
      file,
      relativePath: file.webkitRelativePath || file.name,
    }))
    await uploadBatch(batchFiles, currentParentId.value, {
      onComplete: loadFiles,
      defaultConflictStrategy: 'keep',
    })
  }

  return {
    files,
    loading,
    keyword,
    currentParentId,
    breadcrumbStack,
    sortField,
    sortOrder,
    selectedIds,
    previewOpen,
    previewTarget,
    moveCopyOpen,
    moveCopyType,
    moveCopyTargets,
    shareOpen,
    shareEditTarget,
    emailShareOpen,
    uploadConflictOpen,
    uploadConflicts,

    folders,
    selectedFiles,
    shareModalItems,
    shareModalItemIds,
    isAllSelected,
    hasMixedSelection,
    isSearching,
    displayFiles,

    loadFiles,
    init,
    setSortField,
    toggleSortOrder,
    toggleSort,
    setSort,
    handleSearch,
    clearSearch,
    enterFolder,
    navigateToBreadcrumb,
    toggleSelect,
    toggleSelectAll,
    clearSelection,
    openPreview,
    openMoveCopy,
    handleMoveCopyResult,
    handleDelete,
    handleBatchDelete,
    handleBatchDownload,
    openShareModal,
    openShareModalFor,
    closeShareModal,
    handleCreateShare,
    handleUpdateShare,
    handleShareConfirm,
    openEmailShareModal,
    openEmailShareModalFor,
    closeEmailShareModal,
    handleEmailShareSent,
    openUploadConflict,
    handleUploadConflictConfirm,
    handleUploadConflictCancel,
    uploadFiles,
    uploadFolder,
  }
}
